package com.aimotion.handsfree.gesture

import android.app.admin.DeviceAdminReceiver

/** Exists solely so the app can call [android.app.admin.DevicePolicyManager.lockNow] for the
 * "screen off" gesture. Android gives an ordinary app no API to turn the display off; becoming a
 * device admin with the force-lock policy is the only sanctioned route, which is why this needs
 * a separate, explicit "Activate device admin" grant from the user.
 *
 * The policy list in res/xml/device_admin.xml is deliberately limited to force-lock — this
 * declares no power to wipe data, set passwords, or restrict the device. Deactivate it any time
 * in Settings > Security > Device admin apps; the app keeps working, only the lock gesture stops.
 */
class AirSensorDeviceAdminReceiver : DeviceAdminReceiver()
