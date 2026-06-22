/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */

package com.android.provision;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Helper used to install one or more APK files with progress/error/completion callbacks.
 */
public class ApkInstallHelper {
    private static final String TAG = "ApkInstallHelper";
    private static final String ACTION_INSTALL_COMMIT = "com.android.provision.action.APK_INSTALL_COMMIT";

    public static final int ERROR_INVALID_INPUT = -1001;
    public static final int ERROR_INSTALL_IN_PROGRESS = -1002;
    public static final int ERROR_INVALID_APK = -1003;
    public static final int ERROR_CANCELED = -1004;

    private final Context mContext;
    private final PackageInstaller mPackageInstaller;
    private final Handler mMainHandler;
    private final Object mLock = new Object();
    private final ArrayDeque<InstallRequest> mPendingRequests = new ArrayDeque<>();

    private InstallCallback mInstallCallback;
    private InstallRequest mCurrentRequest;
    private boolean mReceiverRegistered;
    private boolean mSessionCallbackRegistered;

    private int mBatchTotalCount;
    private int mBatchSuccessCount;
    private int mBatchFailedCount;

    private final PackageInstaller.SessionCallback mSessionCallback =
            new PackageInstaller.SessionCallback() {
                @Override
                public void onCreated(int sessionId) {
                    // no-op
                }

                @Override
                public void onBadgingChanged(int sessionId) {
                    // no-op
                }

                @Override
                public void onActiveChanged(int sessionId, boolean active) {
                    // no-op
                }

                @Override
                public void onProgressChanged(int sessionId, float progress) {
                    final InstallRequest request;
                    synchronized (mLock) {
                        if (mCurrentRequest == null || mCurrentRequest.sessionId != sessionId) {
                            return;
                        }
                        request = mCurrentRequest;
                    }
                    postInstallProgress(request, progress);
                }

                @Override
                public void onFinished(int sessionId, boolean success) {
                    // Completion comes from ACTION_INSTALL_COMMIT broadcast, so this is no-op.
                }
            };

    private final BroadcastReceiver mInstallReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!ACTION_INSTALL_COMMIT.equals(intent.getAction())) {
                return;
            }

            final InstallRequest request;
            final int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS,
                    PackageInstaller.STATUS_FAILURE);
            final String message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
            final String installedPackage = intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME);

            synchronized (mLock) {
                if (mCurrentRequest == null) {
                    return;
                }

                int callbackSessionId = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1);
                if (callbackSessionId != mCurrentRequest.sessionId) {
                    return;
                }

                request = mCurrentRequest;
                mCurrentRequest = null;

                if (status == PackageInstaller.STATUS_SUCCESS) {
                    mBatchSuccessCount++;
                } else {
                    mBatchFailedCount++;
                }
            }

            if (status == PackageInstaller.STATUS_SUCCESS) {
                postInstallCompleted(request, TextUtils.isEmpty(installedPackage)
                        ? request.archivePackageName : installedPackage);
            } else {
                postInstallError(request, status,
                        TextUtils.isEmpty(message) ? "install failed" : message, null);
            }

            synchronized (mLock) {
                startNextInstallLocked();
            }
        }
    };

    public interface InstallCallback {
        void onInstallStart(File apkFile, int index, int total);

        void onInstallProgress(File apkFile, int index, int total, float progress);

        void onInstallError(File apkFile, int index, int total,
                int errorCode, String errorMessage, Throwable throwable);

        void onInstallCompleted(File apkFile, int index, int total, String packageName);

        void onBatchCompleted(int total, int successCount, int failedCount);
    }

    public ApkInstallHelper(Context context) {
        mContext = context.getApplicationContext();
        mPackageInstaller = mContext.getPackageManager().getPackageInstaller();
        mMainHandler = new Handler(Looper.getMainLooper());
    }

    public void setInstallCallback(InstallCallback callback) {
        mInstallCallback = callback;
    }

    public boolean installFromDirectory(String directoryPath, boolean recursive) {
        if (TextUtils.isEmpty(directoryPath)) {
            notifyBatchInvalidInput("directory path is empty");
            return false;
        }
        return installFromDirectory(new File(directoryPath), recursive);
    }

    public boolean installFromDirectory(File directory, boolean recursive) {
        if (directory == null || !directory.exists() || !directory.isDirectory()) {
            notifyBatchInvalidInput("directory is invalid: " + directory);
            return false;
        }

        ArrayList<File> apks = new ArrayList<>();
        collectApkFiles(directory, recursive, apks);
        return installApkFiles(apks);
    }

    public boolean installApkPaths(List<String> apkPaths) {
        if (apkPaths == null || apkPaths.isEmpty()) {
            notifyBatchInvalidInput("apk path list is empty");
            return false;
        }

        ArrayList<File> files = new ArrayList<>(apkPaths.size());
        for (String path : apkPaths) {
            files.add(path == null ? null : new File(path));
        }
        return installApkFiles(files);
    }

    public boolean installApkFiles(List<File> apkFiles) {
        if (apkFiles == null || apkFiles.isEmpty()) {
            notifyBatchInvalidInput("apk file list is empty");
            return false;
        }

        synchronized (mLock) {
            if (isInstallingLocked()) {
                postBatchBusyError();
                return false;
            }

            resetBatchStateLocked(apkFiles.size());
            ensureCallbacksRegisteredLocked();

            for (int i = 0; i < apkFiles.size(); i++) {
                File apkFile = apkFiles.get(i);
                int index = i + 1;
                if (isApkFileValid(apkFile)) {
                    mPendingRequests.add(new InstallRequest(apkFile, index, apkFiles.size(),
                            resolveArchivePackageName(apkFile)));
                } else {
                    mBatchFailedCount++;
                    postInstallError(new InstallRequest(apkFile, index, apkFiles.size(), null),
                            ERROR_INVALID_APK,
                            "invalid apk file: " + (apkFile == null ? "null" : apkFile.getAbsolutePath()),
                            null);
                }
            }

            if (mPendingRequests.isEmpty()) {
                postBatchCompleted(mBatchTotalCount, mBatchSuccessCount, mBatchFailedCount);
                return false;
            }

            startNextInstallLocked();
            return true;
        }
    }

    public void release() {
        cancelCurrentBatch("install helper released");
        synchronized (mLock) {
            unregisterCallbacksLocked();
        }
    }

    public void cancelCurrentBatch(String reason) {
        synchronized (mLock) {
            if (!isInstallingLocked()) {
                return;
            }

            String cancelReason = TextUtils.isEmpty(reason) ? "install canceled" : reason;
            InstallRequest canceledRequest = mCurrentRequest;
            int canceledQueuedCount = mPendingRequests.size();

            mPendingRequests.clear();
            if (canceledRequest != null && canceledRequest.sessionId > 0) {
                try {
                    mPackageInstaller.abandonSession(canceledRequest.sessionId);
                } catch (Exception ignored) {
                    // Ignore abandon exception.
                }
            }

            mCurrentRequest = null;
            if (canceledRequest != null) {
                mBatchFailedCount++;
                postInstallError(canceledRequest, ERROR_CANCELED, cancelReason, null);
            }

            mBatchFailedCount += canceledQueuedCount;
            unregisterCallbacksLocked();
            postBatchCompleted(mBatchTotalCount, mBatchSuccessCount, mBatchFailedCount);
        }
    }

    private void startNextInstallLocked() {
        if (mCurrentRequest != null) {
            return;
        }

        InstallRequest nextRequest = mPendingRequests.poll();
        if (nextRequest == null) {
            unregisterCallbacksLocked();
            postBatchCompleted(mBatchTotalCount, mBatchSuccessCount, mBatchFailedCount);
            return;
        }

        mCurrentRequest = nextRequest;
        postInstallStart(nextRequest);
        startInstallSessionLocked(nextRequest);
    }

    private void startInstallSessionLocked(InstallRequest request) {
        PackageInstaller.Session session = null;
        try {
            PackageInstaller.SessionParams params =
                    new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            if (!TextUtils.isEmpty(request.archivePackageName)) {
                params.setAppPackageName(request.archivePackageName);
            }

            request.sessionId = mPackageInstaller.createSession(params);
            session = mPackageInstaller.openSession(request.sessionId);
            copyApkIntoSession(request.apkFile, session);
            session.commit(createStatusIntentSender(request.sessionId));
        } catch (Exception e) {
            Log.e(TAG, "Failed to install " + request.apkFile, e);
            mCurrentRequest = null;
            mBatchFailedCount++;
            postInstallError(request, PackageInstaller.STATUS_FAILURE,
                    "install exception: " + e.getMessage(), e);
            if (request.sessionId > 0) {
                try {
                    mPackageInstaller.abandonSession(request.sessionId);
                } catch (Exception ignored) {
                    // Ignore abandon exception.
                }
            }
            startNextInstallLocked();
        } finally {
            if (session != null) {
                try {
                    session.close();
                } catch (Exception ignored) {
                    // Ignore close exception.
                }
            }
        }
    }

    private void copyApkIntoSession(File apkFile, PackageInstaller.Session session) throws IOException {
        try (FileInputStream inputStream = new FileInputStream(apkFile);
             OutputStream outputStream = session.openWrite("base.apk", 0, apkFile.length())) {
            byte[] buffer = new byte[64 * 1024];
            int c;
            while ((c = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, c);
            }
            session.fsync(outputStream);
        }
    }

    private android.content.IntentSender createStatusIntentSender(int sessionId) {
        Intent callbackIntent = new Intent(ACTION_INSTALL_COMMIT).setPackage(mContext.getPackageName());
        PendingIntent pendingIntent = PendingIntent.getBroadcast(
                mContext,
                sessionId,
                callbackIntent,
                PendingIntent.FLAG_UPDATE_CURRENT);
        return pendingIntent.getIntentSender();
    }

    private void collectApkFiles(File directory, boolean recursive, List<File> outFiles) {
        File[] children = directory.listFiles();
        if (children == null || children.length == 0) {
            return;
        }

        Arrays.sort(children, Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER));
        for (File child : children) {
            if (child == null) {
                continue;
            }
            if (child.isDirectory()) {
                if (recursive) {
                    collectApkFiles(child, true, outFiles);
                }
                continue;
            }
            if (child.isFile() && child.getName().toLowerCase(Locale.ROOT).endsWith(".apk")) {
                outFiles.add(child);
            }
        }
    }

    private String resolveArchivePackageName(File apkFile) {
        if (apkFile == null) {
            return null;
        }
        PackageManager packageManager = mContext.getPackageManager();
        PackageInfo packageInfo = packageManager.getPackageArchiveInfo(apkFile.getAbsolutePath(), 0);
        return packageInfo == null ? null : packageInfo.packageName;
    }

    private boolean isApkFileValid(File file) {
        return file != null
                && file.exists()
                && file.isFile()
                && file.length() > 0
                && file.getName().toLowerCase(Locale.ROOT).endsWith(".apk");
    }

    private void ensureCallbacksRegisteredLocked() {
        if (!mReceiverRegistered) {
            IntentFilter filter = new IntentFilter(ACTION_INSTALL_COMMIT);
            mContext.registerReceiver(mInstallReceiver, filter);
            mReceiverRegistered = true;
        }

        if (!mSessionCallbackRegistered) {
            mPackageInstaller.registerSessionCallback(mSessionCallback, mMainHandler);
            mSessionCallbackRegistered = true;
        }
    }

    private void unregisterCallbacksLocked() {
        if (mReceiverRegistered) {
            mContext.unregisterReceiver(mInstallReceiver);
            mReceiverRegistered = false;
        }

        if (mSessionCallbackRegistered) {
            mPackageInstaller.unregisterSessionCallback(mSessionCallback);
            mSessionCallbackRegistered = false;
        }
    }

    private void resetBatchStateLocked(int totalCount) {
        mPendingRequests.clear();
        mCurrentRequest = null;
        mBatchTotalCount = totalCount;
        mBatchSuccessCount = 0;
        mBatchFailedCount = 0;
    }

    private boolean isInstallingLocked() {
        return mCurrentRequest != null || !mPendingRequests.isEmpty();
    }

    private void notifyBatchInvalidInput(final String reason) {
        post(new Runnable() {
            @Override
            public void run() {
                if (mInstallCallback != null) {
                    mInstallCallback.onInstallError(null, 0, 0, ERROR_INVALID_INPUT, reason, null);
                }
            }
        });
    }

    private void postBatchBusyError() {
        post(new Runnable() {
            @Override
            public void run() {
                if (mInstallCallback != null) {
                    mInstallCallback.onInstallError(null, 0, 0, ERROR_INSTALL_IN_PROGRESS,
                            "install task already running", null);
                }
            }
        });
    }

    private void postInstallStart(final InstallRequest request) {
        post(new Runnable() {
            @Override
            public void run() {
                if (mInstallCallback != null) {
                    mInstallCallback.onInstallStart(request.apkFile, request.index, request.total);
                }
            }
        });
    }

    private void postInstallProgress(final InstallRequest request, final float progress) {
        post(new Runnable() {
            @Override
            public void run() {
                if (mInstallCallback != null) {
                    mInstallCallback.onInstallProgress(request.apkFile, request.index,
                            request.total, progress);
                }
            }
        });
    }

    private void postInstallError(final InstallRequest request,
            final int errorCode,
            final String message,
            final Throwable throwable) {
        post(new Runnable() {
            @Override
            public void run() {
                if (mInstallCallback != null) {
                    mInstallCallback.onInstallError(request.apkFile, request.index,
                            request.total, errorCode, message, throwable);
                }
            }
        });
    }

    private void postInstallCompleted(final InstallRequest request, final String packageName) {
        post(new Runnable() {
            @Override
            public void run() {
                if (mInstallCallback != null) {
                    mInstallCallback.onInstallCompleted(request.apkFile, request.index,
                            request.total, packageName);
                }
            }
        });
    }

    private void postBatchCompleted(final int total, final int successCount, final int failedCount) {
        post(new Runnable() {
            @Override
            public void run() {
                if (mInstallCallback != null) {
                    mInstallCallback.onBatchCompleted(total, successCount, failedCount);
                }
            }
        });
    }

    private void post(Runnable runnable) {
        if (Looper.myLooper() == mMainHandler.getLooper()) {
            runnable.run();
        } else {
            mMainHandler.post(runnable);
        }
    }

    private static final class InstallRequest {
        final File apkFile;
        final int index;
        final int total;
        final String archivePackageName;
        int sessionId;

        InstallRequest(File apkFile, int index, int total, String archivePackageName) {
            this.apkFile = apkFile;
            this.index = index;
            this.total = total;
            this.archivePackageName = archivePackageName;
            this.sessionId = -1;
        }
    }
}



