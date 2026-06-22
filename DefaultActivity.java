/*
 * Copyright (C) 2008 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.provision;

import android.app.Activity;
import android.content.ComponentName;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.provider.Settings;
import android.os.Handler;
import android.util.Log;
import java.io.File;

/**
 * Application that sets the provisioned bit, like SetupWizard does.
 */
public class DefaultActivity extends Activity {

    private static final String TAG = "SetupWizard";

    private static final String PRELOAD_APK_DIR = "/system/preload/";


    @Override
    protected void onCreate(Bundle icicle) {
        super.onCreate(icicle);
        ApkInstallHelper helper = new ApkInstallHelper(this);
        helper.setInstallCallback(callback);
        new Handler(getMainLooper()).post(()->helper.installFromDirectory(PRELOAD_APK_DIR,false));
    }

    private void setupFinish() {
        Settings.Global.putInt(getContentResolver(), Settings.Global.DEVICE_PROVISIONED, 1);
        Settings.Secure.putInt(getContentResolver(), Settings.Secure.USER_SETUP_COMPLETE, 1);
        PackageManager pm = getPackageManager();
        ComponentName name = new ComponentName(this, DefaultActivity.class);
        pm.setComponentEnabledSetting(name, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP);
        finish();
    }

    ApkInstallHelper.InstallCallback callback = new ApkInstallHelper.InstallCallback() {
        @Override
        public void onInstallStart(File apkFile, int index, int total) {
            Log.i(TAG, "Start installing apk: " + (apkFile!=null?apkFile.getName():"NULL") + ", index: " + index + ", total: " + total);
        }

        @Override
        public void onInstallProgress(File apkFile, int index, int total, float progress) {
            Log.i(TAG, "Installing apk: " + (apkFile!=null?apkFile.getName():"NULL") + ", index: " + index + ", total: " + total + ", progress: " + progress);
        }

        @Override
        public void onInstallError(File apkFile, int index, int total, int errorCode, String errorMessage, Throwable throwable) {
            Log.e(TAG, "Failed to install apk: " + (apkFile!=null?apkFile.getName():"NULL") + ", index: " + index + ", total: " + total
                    + ", errorCode: " + errorCode + ", errorMessage: " + errorMessage, throwable);
        }

        @Override
        public void onInstallCompleted(File apkFile, int index, int total, String packageName) {
            Log.i(TAG, "Completed installing apk: " + (apkFile!=null?apkFile.getName():"NULL") + ", index: " + index + ", total: " + total + ", packageName: " + packageName);

        }

        @Override
        public void onBatchCompleted(int total, int successCount, int failedCount) {
            Log.i(TAG, "Completed installing apk batch, total: " + total + ", successCount: " + successCount + ", failedCount: " + failedCount);
            setupFinish();
        }
    };


}

