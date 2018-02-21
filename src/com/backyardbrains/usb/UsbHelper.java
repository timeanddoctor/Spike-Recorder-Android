package com.backyardbrains.usb;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.util.ArraySet;
import com.backyardbrains.audio.AbstractInputSource;
import com.backyardbrains.audio.AudioService;
import com.backyardbrains.utils.SpikerBoxHardwareType;
import com.crashlytics.android.Crashlytics;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.backyardbrains.utils.LogUtils.LOGD;
import static com.backyardbrains.utils.LogUtils.makeLogTag;

/**
 * @author Tihomir Leka <ticapeca at gmail.com>
 */
public class UsbHelper implements SpikerBoxDetector.OnSpikerBoxDetectionListener {

    @SuppressWarnings("WeakerAccess") static final String TAG = makeLogTag(UsbHelper.class);

    private static final String ACTION_USB_PERMISSION = "com.backyardbrains.usb.USB_PERMISSION";
    private static final String EXTRA_DETECTION = "com.backyardbrains.extra.DETECTION";

    private static final IntentFilter USB_INTENT_FILTER;

    static {
        USB_INTENT_FILTER = new IntentFilter();
        USB_INTENT_FILTER.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        USB_INTENT_FILTER.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        USB_INTENT_FILTER.addAction(ACTION_USB_PERMISSION);
    }

    /**
     * Interface definition for a callback to be invoked when different USB events and communication events occur.
     */
    public interface UsbListener {
        /**
         * Called when new supported usb device is attached.
         *
         * @param deviceName Name of the connected usb device.
         * @param hardwareType Type of the connected usb device hardware. One of {@link SpikerBoxHardwareType}.
         */
        void onDeviceAttached(@NonNull String deviceName, @SpikerBoxHardwareType int hardwareType);

        /**
         * Called when previously connected usb device is detached.
         *
         * @param deviceName Name of the connected usb device.
         */
        void onDeviceDetached(@NonNull String deviceName);

        /**
         * Called when connected usb device starts transferring data.
         */
        void onDataTransferStart();

        /**
         * Called when connected usb device stops transferring data.
         */
        void onDataTransferEnd();

        /**
         * Called when communication permission for the connected usb device has been granted by the user.
         */
        void onPermissionGranted();

        /**
         * Called when communication permission for the connected usb device has been denied by the user.
         */
        void onPermissionDenied();
    }

    // Receives broadcast sent by Android related to USB device interface
    private final BroadcastReceiver usbConnectionReceiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            final UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            if (ACTION_USB_PERMISSION.equals(action)) {
                boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
                if (granted) {
                    // check should we do detection or start the communication
                    boolean detection = intent.getBooleanExtra(EXTRA_DETECTION, true);
                    if (!detection) { // we already detected the hardware type
                        if (listener != null) listener.onPermissionGranted();

                        communicationThread = new CommunicationThread(device);
                        communicationThread.start();
                    } else {
                        detector.startDetection(device);
                    }
                } else {
                    // remove denied device from local collections
                    removeDevice(device);
                    // and inform listener about denied permission
                    if (listener != null) listener.onPermissionDenied();
                }
            } else {
                if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) close();

                // refresh list of connected and/or disconnected usb devices
                refreshDevices(context);
            }
        }
    };

    // Opens communication between connected BYB USB hardware and app.
    private class CommunicationThread extends Thread {

        private final UsbDevice device;

        CommunicationThread(@NonNull UsbDevice device) {
            this.device = device;
        }

        @Override public void run() {
            final UsbDeviceConnection connection = manager.openDevice(device);
            if (AbstractUsbInputSource.isSupported(device)) {
                usbDevice = AbstractUsbInputSource.createUsbDevice(device, connection, service);
                if (usbDevice != null) {
                    if (usbDevice.open()) {
                        if (listener != null) listener.onDataTransferStart();

                        if (usbDevice != null) usbDevice.start();

                        try {
                            Thread.sleep(2000);
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }

                        if (usbDevice != null) usbDevice.checkHardwareType();
                    } else {
                        LOGD(TAG, "PORT NOT OPEN");
                        Crashlytics.logException(new RuntimeException("Failed to open USB communication port!"));
                    }
                } else {
                    LOGD(TAG, "PORT IS NULL");
                    Crashlytics.logException(new RuntimeException("Failed to create USB device!"));
                }
            } else {
                LOGD(TAG, "DEVICE NOT SUPPORTED");
                Crashlytics.logException(new RuntimeException("Connected USB device is not supported!"));
            }
        }
    }

    @SuppressWarnings("WeakerAccess") final AbstractInputSource.OnSamplesReceivedListener service;
    @SuppressWarnings("WeakerAccess") final UsbManager manager;
    @SuppressWarnings("WeakerAccess") final SpikerBoxDetector detector;
    @SuppressWarnings("WeakerAccess") final UsbHelper.UsbListener listener;

    @SuppressWarnings("WeakerAccess") AbstractUsbInputSource usbDevice;

    @SuppressWarnings("WeakerAccess") CommunicationThread communicationThread;

    private final List<UsbDevice> devices = new ArrayList<>();
    private final Map<String, UsbDevice> devicesMap = new HashMap<>();

    public UsbHelper(@NonNull Context context, @NonNull AudioService service, @Nullable UsbListener listener) {
        this.service = service;
        this.listener = listener;

        this.manager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        this.detector = SpikerBoxDetector.get(manager, this);

        refreshDevices(context);
    }

    /**
     * Starts the helper.
     */
    public void start(@NonNull Context context) {
        context.registerReceiver(usbConnectionReceiver, USB_INTENT_FILTER);
    }

    /**
     * Stops reading incoming data from the connected usb device. Communication with the device is not finished, just
     * paused until {@link #resume()} is called.
     */
    public void pause() {
        if (usbDevice != null) usbDevice.pause();
    }

    /**
     * Starts reading incoming data from the connected usb device.
     */
    public void resume() {
        if (usbDevice != null) usbDevice.resume();
    }

    /**
     * Stops the helper.
     */
    public void stop(@NonNull Context context) {
        context.unregisterReceiver(usbConnectionReceiver);
    }

    /**
     * Returns USB device for the specified {@code index} or {@code null} if there are no connected devices or index is
     * out of range.
     */
    @Nullable public UsbDevice getDevice(int index) {
        if (index < 0 || index >= devices.size()) return null;

        return devices.get(index);
    }

    /**
     * Returns number of currently connected serial communication capable devices.
     */
    public int getDevicesCount() {
        return devicesMap.size();
    }

    /**
     * Returns currently connected SpikerBox device, or {@code null} if none is connected.
     */
    @Nullable public AbstractUsbInputSource getUsbDevice() {
        return usbDevice;
    }

    /**
     * Initiates communication with usb device with specified {@code deviceName} by requesting a permission to access
     * the device. If request is granted by the user the communication with the device will automatically be opened.
     *
     * @throws IllegalArgumentException if the device with specified {@code deviceName} is not connected.
     */
    public void requestPermission(@NonNull Context context, @NonNull String deviceName, boolean detection)
        throws IllegalArgumentException {
        final UsbDevice device = devicesMap.get(deviceName);
        if (device != null) {
            final Intent intent = new Intent(ACTION_USB_PERMISSION).putExtra(EXTRA_DETECTION, detection);
            final PendingIntent pi = PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT);

            manager.requestPermission(device, pi);

            return;
        }

        throw new IllegalArgumentException("Device " + deviceName + " is not connected!");
    }

    /**
     * Closes the communication with the currently connected usb device if any is connected. After calling this method
     * caller will need to request permission to connect with the device.
     */
    public void close() {
        if (usbDevice != null) {
            usbDevice.stop();
            usbDevice = null;
        }
        communicationThread = null;

        if (listener != null) listener.onDataTransferEnd();
    }

    //==========================================================
    // IMPLEMENTATION OF OnSpikerBoxDetectionListener INTERFACE
    //==========================================================

    @Override public void onSpikerBoxDetected(@NonNull UsbDevice device, @SpikerBoxHardwareType int hardwareType) {
        // inform listener that attached device has been detected and it's ready for communication
        if (listener != null) listener.onDeviceAttached(device.getDeviceName(), hardwareType);
    }

    @Override public void onSpikerBoxDetectionFailure(@NonNull UsbDevice device) {
        // we couldn't detect the hardware type of the connected usb device
        // so remove device from local collections
        removeDevice(device);
        // and inform listener about removed device
        if (listener != null) listener.onDeviceDetached(device.getDeviceName());
    }

    @Override public void onSpikerBoxDetectionError(@NonNull String deviceName, @NonNull String reason) {
        // remove device from local collections
        final UsbDevice device = devicesMap.get(deviceName);
        if (device != null) removeDevice(device);

        // TODO: 05-Feb-18 SHOW THE REASON WHEY CONNECTION/COMMUNICATION WITH DEVICE FAILED
    }

    //==========================================================
    // PRIVATE METHODS
    //==========================================================

    // Refreshes the connected devices list with only supported (serial and HID) ones.
    @SuppressWarnings("WeakerAccess") void refreshDevices(@NonNull Context context) {
        final List<UsbDevice> addedDevices = new ArrayList<>();
        final List<UsbDevice> removedDevices = new ArrayList<>();
        final List<UsbDevice> devices = new ArrayList<>(manager.getDeviceList().values());

        // find newly added devices
        for (UsbDevice device : devices) {
            if (AbstractUsbInputSource.isSupported(device) && !devicesMap.containsKey(device.getDeviceName())) {
                addedDevices.add(device);
            }
        }

        // find newly removed devices
        final Set<String> newListKeys = new ArraySet<>();
        for (UsbDevice device : devices)
            newListKeys.add(device.getDeviceName());
        for (UsbDevice device : this.devices) {
            if (!newListKeys.contains(device.getDeviceName())) {
                removedDevices.add(device);
            }
        }

        // save newly added devices and start the hardware type detection process
        for (UsbDevice device : addedDevices) {
            // add device to local collections
            addDevice(device);
            // let's just do a quick check if we can detect hardware type through VID and PID
            final @SpikerBoxHardwareType int hardwareType;
            if ((hardwareType = AbstractUsbInputSource.getHardwareType(device)) != SpikerBoxHardwareType.UNKNOWN) {
                listener.onDeviceAttached(device.getDeviceName(), hardwareType);
            } else {
                requestPermission(context, device.getDeviceName(), true);
            }
        }

        // clear newly removed devices and inform listener about them
        for (UsbDevice device : removedDevices) {
            // remove device from local collections
            removeDevice(device);
            // if detection for the removed device is in progress, cancel it
            detector.cancelDetection(device.getDeviceName());
            // inform listener about removed device
            if (listener != null) listener.onDeviceDetached(device.getDeviceName());
        }
    }

    // Adds specified device to local collections
    private void addDevice(@NonNull UsbDevice device) {
        this.devicesMap.put(device.getDeviceName(), device);
        this.devices.add(device);
    }

    // Removes specified device from local collections
    @SuppressWarnings("WeakerAccess") void removeDevice(@NonNull UsbDevice device) {
        this.devicesMap.remove(device.getDeviceName());
        this.devices.remove(device);
    }
}