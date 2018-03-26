package com.backyardbrains.usb;

import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import com.backyardbrains.utils.SampleStreamUtils;
import com.felhr.usbserial.UsbSerialDevice;
import com.felhr.usbserial.UsbSerialInterface;
import java.util.Locale;

import static com.backyardbrains.utils.LogUtils.makeLogTag;

/**
 * Implementation of {@link AbstractUsbSampleSource} capable of USB serial communication with BYB hardware.
 *
 * @author Tihomir Leka <ticapeca at gmail.com.
 */

public class SerialSampleSource extends AbstractUsbSampleSource {

    private static final String TAG = makeLogTag(SerialSampleSource.class);

    // Arduino Vendor ID
    private static final int ARDUINO_VENDOR_ID_1 = 0x2341;
    // Arduino Vendor ID
    private static final int ARDUINO_VENDOR_ID_2 = 0x2A03;
    // FTDI Vendor ID
    private static final int FTDI_VENDOR_ID = 0x0403;
    // CH340 Chinese boards Vendor ID
    private static final int CH340_VENDOR_ID = 0x1A86;

    private static final int BAUD_RATE = 230400;

    private static final String MSG_CONFIG_PREFIX = "conf ";
    private static final String MSG_SAMPLE_RATE = "s:%d;";
    private static final String MSG_CHANNELS = "c:%d;";

    private static final String MSG_BOARD_TYPE_INQUIRY = "b:;\n";
    private static final String MSG_CONFIG_SAMPLE_RATE_AND_CHANNELS;

    static {
        MSG_CONFIG_SAMPLE_RATE_AND_CHANNELS =
            MSG_CONFIG_PREFIX + String.format(Locale.getDefault(), MSG_SAMPLE_RATE, SampleStreamUtils.SAMPLE_RATE)
                + String.format(Locale.getDefault(), MSG_CHANNELS, 1) + "\n";
    }

    private UsbSerialDevice serialDevice;

    private SerialSampleSource(@NonNull UsbDevice device, @NonNull UsbDeviceConnection connection,
        @Nullable OnSamplesReceivedListener listener) {
        super(device, listener);

        serialDevice = UsbSerialDevice.createUsbSerialDevice(device, connection);
    }

    /**
     * Creates and returns new {@link AbstractUsbSampleSource} based on specified {@code device} capable for serial
     * communication,
     * or {@code null} if specified device is not supported by BYB.
     *
     * @return BYB USB device interface configured for serial communication
     */
    public static AbstractUsbSampleSource createUsbDevice(@NonNull UsbDevice device,
        @NonNull UsbDeviceConnection connection, @Nullable OnSamplesReceivedListener listener) {
        return new SerialSampleSource(device, connection, listener);
    }

    /**
     * Checks whether specified {@code device} is serial capable device supported by BYB.
     */
    public static boolean isSupported(@NonNull UsbDevice device) {
        int vid = device.getVendorId();
        return UsbSerialDevice.isSupported(device) && (vid == BYB_VENDOR_ID || vid == ARDUINO_VENDOR_ID_1
            || vid == ARDUINO_VENDOR_ID_2 || vid == FTDI_VENDOR_ID || vid == CH340_VENDOR_ID);
    }

    @Override protected void onInputStart() {
        // prepare serial usb device for communication
        if (serialDevice != null) {
            serialDevice.setBaudRate(BAUD_RATE);
            serialDevice.setDataBits(UsbSerialInterface.DATA_BITS_8);
            serialDevice.setStopBits(UsbSerialInterface.STOP_BITS_1);
            serialDevice.setParity(UsbSerialInterface.PARITY_NONE);
            serialDevice.setFlowControl(UsbSerialInterface.FLOW_CONTROL_OFF);
        }

        super.onInputStart();
    }

    /**
     * {@inheritDoc}
     */
    @Override protected void onInputStop() {
        if (serialDevice != null) serialDevice.close();
    }

    /**
     * {@inheritDoc}
     */
    @Override public boolean open() {
        return serialDevice != null && serialDevice.open();
    }

    /**
     * {@inheritDoc}
     */
    @Override public void write(byte[] buffer) {
        if (serialDevice != null) serialDevice.write(buffer);
    }

    /**
     * {@inheritDoc}
     */
    @Override public void startReadingStream() {
        // start reading data from USB
        if (serialDevice != null) {
            serialDevice.read(new UsbSerialInterface.UsbReadCallback() {
                @Override public void onReceivedData(byte[] bytes) {
                    writeToBuffer(bytes);
                }
            });
        }

        // we don't actually start the stream, it's automatically stared after connection, but we should
        // configure sample rate and num of channels at startup
        write(MSG_CONFIG_SAMPLE_RATE_AND_CHANNELS.getBytes());
    }

    /**
     * {@inheritDoc}
     */
    @Override public void checkHardwareType() {
        write(MSG_BOARD_TYPE_INQUIRY.getBytes());
    }
}