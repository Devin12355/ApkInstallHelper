# ApkInstallHelper
install apk code
1. you need preset permission file in your system(frameworks/base/data/etc/com.android.provision.xml)
2. add permission in the XML :

<permissions>
    <privapp-permissions package="com.android.provision">
            <permission name="android.permission.WRITE_SECURE_SETTINGS"/>
            <permission name="android.permission.INSTALL_PACKAGES"/>
    </privapp-permissions>
</permissions>
