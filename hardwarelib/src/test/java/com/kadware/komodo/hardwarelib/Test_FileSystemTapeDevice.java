/*
 * Copyright (c) 2018-2019 by Kurt Duncan - All Rights Reserved
 */

package com.kadware.komodo.hardwarelib;

import com.kadware.komodo.baselib.*;
import com.kadware.komodo.hardwarelib.exceptions.InvalidBlockSizeException;
import com.kadware.komodo.hardwarelib.exceptions.InvalidTrackCountException;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Random;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import static org.junit.Assert.*;

/**
 * Unit tests for FileSystemDiskDevice class
 */
public class Test_FileSystemTapeDevice {

    @Rule
    public ExpectedException _exception = ExpectedException.none();

    public static class TestChannelModule extends ChannelModule {

        private final List<DeviceIOInfo> _ioList = new LinkedList<>();

        private TestChannelModule() {
            super(ChannelModuleType.Byte, "TESTCM");
        }

        //  Only for satisfying the compiler
        protected Tracker createTracker(
            Processor p,
            InputOutputProcessor iop,
            ChannelProgram cp,
            ArraySlice buffer
        ) {
            return null;
        }

        //  This is the real thing
        void submitAndWait(
            final Device target,
            final DeviceIOInfo deviceIoInfo
        ) {
            if (target.handleIo(deviceIoInfo)) {
                synchronized (_ioList) {
                    _ioList.add(deviceIoInfo);
                }

                synchronized (deviceIoInfo) {
                    while (deviceIoInfo._status == DeviceStatus.InProgress) {
                        try {
                            deviceIoInfo.wait(1);
                        } catch (InterruptedException ex) {
                            System.out.println(ex.getMessage());
                        }
                    }
                }
            }
        }

        public void run() {
            while (!_workerTerminate) {
                synchronized (_workerThread) {
                    try {
                        _workerThread.wait(1000);
                    } catch (InterruptedException ex) {
                        System.out.println(ex.getMessage());
                    }
                }

                synchronized (_ioList) {
                    Iterator<DeviceIOInfo> iter = _ioList.iterator();
                    while (iter.hasNext()) {
                        DeviceIOInfo ioInfo = iter.next();
                        if (ioInfo._status != DeviceStatus.InProgress) {
                            iter.remove();
                            ioInfo._status.notify();
                        }
                    }
                }
            }
        }
    }

    public static class TestDevice extends FileSystemTapeDevice {

        TestDevice() { super("TEST"); }
    }

    private static int nextFileIndex = 1;

    /**
     * Prepends the system-wide temporary path to the given base name if found
     * @return cooked path/file name
     */
    private static String getTestFileName(
    ) {
        String pathName = System.getProperty("java.io.tmpdir");
        return String.format("%sTEST%04d.vol", pathName == null ? "" : pathName, nextFileIndex++);
    }

    /**
     * For some reason, the delete occasionally fails with the file assigned to another process.
     * I don't know why, so we do this just in case.
     */
    private static void deleteTestFile(
        final String fileName
    ) {
        boolean done = false;
        while (!done) {
            try {
                Files.delete(FileSystems.getDefault().getPath(fileName));
                done = true;
            } catch (Exception ex) {
                System.out.println("Retrying delete...");
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ex2) {
                    System.out.println(ex.getMessage());
                }
            }
        }
    }

    @Test
    public void create(
    ) {
        FileSystemTapeDevice d = new FileSystemTapeDevice("TAPE0");
        assertEquals("TAPE0", d._name);
        assertEquals(NodeCategory.Device, d._category);
        Assert.assertEquals(DeviceModel.FileSystemTape, d._deviceModel);
        assertEquals(DeviceType.Tape, d._deviceType);
    }

    @Test
    public void canConnect_success(
    ) {
        ByteChannelModule cm = new ByteChannelModule("CM0");
        FileSystemDiskDevice d = new FileSystemDiskDevice("TAPE0");
        assertTrue(d.canConnect(cm));
    }

    @Test
    public void canConnect_failure(
    ) {
        FileSystemTapeDevice d = new FileSystemTapeDevice("TAPE0");
        assertFalse(d.canConnect(new FileSystemDiskDevice("DISK1")));
        assertFalse(d.canConnect(new FileSystemTapeDevice("TAPE0")));
        assertFalse(d.canConnect(new WordChannelModule("CM1-1")));
        assertFalse(d.canConnect(new MainStorageProcessor("MSP0",
                                                          InventoryManager.FIRST_MAIN_STORAGE_PROCESSOR_UPI_INDEX,
                                                          InventoryManager.MAIN_STORAGE_PROCESSOR_SIZE)));
        assertFalse(d.canConnect(new InputOutputProcessor("IOP0", InventoryManager.FIRST_INPUT_OUTPUT_PROCESSOR_UPI_INDEX)));
        assertFalse(d.canConnect(new InstructionProcessor("IP0", InventoryManager.FIRST_INSTRUCTION_PROCESSOR_UPI_INDEX)));
    }

    @Test
    public void createVolume(
    ) throws Exception {
        String fileName = getTestFileName();
        FileSystemTapeDevice.createVolume(fileName);
        deleteTestFile(fileName);
    }

    @Test
    public void createVolume_badPath(
    ) throws Exception {
        _exception.expect(FileNotFoundException.class);
        FileSystemTapeDevice.createVolume("/blah/blah/blah/TEST.vol");
    }

    @Test
    public void hasByteInterface(
    ) {
        TestDevice d = new TestDevice();
        assertTrue(d.hasByteInterface());
    }

    @Test
    public void hasWordInterface(
    ) {
        TestDevice d = new TestDevice();
        assertFalse(d.hasWordInterface());
    }

    @Test
    public void ioGetInfo_successful(
    ) throws Exception {
        String fileName = getTestFileName();
        FileSystemTapeDevice.createVolume(fileName);

        TestChannelModule cm = new TestChannelModule();
        TestDevice d = new TestDevice();
        d.mount(fileName);
        assertTrue(d.setReady(true));

        DeviceIOInfo ioInfo = new DeviceIOInfo.ByteTransferBuilder().setSource(cm)
                                                                    .setIOFunction(IOFunction.GetInfo)
                                                                    .setTransferCount(128)
                                                                    .build();
        cm.submitAndWait(d, ioInfo);
        assertEquals(DeviceStatus.Successful, ioInfo._status);
        ArraySlice as = new ArraySlice(new long[28]);
        as.unpack(ioInfo._byteBuffer, false);

        int flags = (int) Word36.getS1(as.get(0));
        boolean resultIsReady = (flags & 040) != 0;
        boolean resultIsMounted = (flags & 004) != 0;
        boolean resultIsWriteProtected = (flags & 002) != 0;
        DeviceModel resultModel = DeviceModel.getValue((int) Word36.getS2(as.get(0)));
        DeviceType resultType = DeviceType.getValue((int) Word36.getS3(as.get(0)));

        assertTrue(resultIsReady);
        assertTrue(resultIsMounted);
        assertTrue(resultIsWriteProtected);
        assertEquals(DeviceModel.FileSystemTape, resultModel);
        assertEquals(DeviceType.Tape, resultType);
        assertFalse(d._unitAttentionFlag);

        d.unmount();
        deleteTestFile(fileName);
    }

    @Test
    public void ioRead_fail_notReady(
    ) {
        TestChannelModule cm = new TestChannelModule();
        TestDevice d = new TestDevice();

        long blockId = 5;
        int blockSize = 128;
        DeviceIOInfo ioInfoRead = new DeviceIOInfo.ByteTransferBuilder().setSource(cm)
                                                                        .setIOFunction(IOFunction.Read)
                                                                        .setBlockId(blockId)
                                                                        .setTransferCount(blockSize)
                                                                        .build();

        cm.submitAndWait(d, ioInfoRead);
        assertEquals(DeviceStatus.NotReady, ioInfoRead._status);
    }

    @Test
    public void ioRead_fail_unitAttention(
    ) throws Exception {
        String fileName = getTestFileName();
        FileSystemTapeDevice.createVolume(fileName);

        TestChannelModule cm = new TestChannelModule();
        TestDevice d = new TestDevice();
        assertTrue(d.mount(fileName));
        assertTrue(d.setReady(true));

        DeviceIOInfo ioInfoRead = new DeviceIOInfo.ByteTransferBuilder().setSource(cm)
                                                                        .setIOFunction(IOFunction.Read)
                                                                        .setTransferCount(1024)
                                                                        .build();
        cm.submitAndWait(d, ioInfoRead);
        assertEquals(DeviceStatus.UnitAttention, ioInfoRead._status);

        d.unmount();
        deleteTestFile(fileName);
    }

    @Test
    public void ioReset_successful(
    ) throws Exception {
        String fileName = getTestFileName();
        FileSystemTapeDevice.createVolume(fileName);

        TestChannelModule cm = new TestChannelModule();
        FileSystemTapeDevice d = new TestDevice();
        d.mount(fileName);
        d.setReady(true);

        DeviceIOInfo ioInfo = new DeviceIOInfo.NonTransferBuilder().setSource(cm)
                                                                   .setIOFunction(IOFunction.Reset)
                                                                   .build();
        cm.submitAndWait(d, ioInfo);
        assertEquals(DeviceStatus.Successful, ioInfo._status);

        d.unmount();
        deleteTestFile(fileName);
    }

    @Test
    public void ioReset_failed_notReady(
    ) {
        TestChannelModule cm = new TestChannelModule();
        FileSystemTapeDevice d = new FileSystemTapeDevice("TAPE0");
        DeviceIOInfo ioInfo = new DeviceIOInfo.NonTransferBuilder().setSource(cm)
                                                                   .setIOFunction(IOFunction.Reset)
                                                                   .build();
        cm.submitAndWait(d, ioInfo);
        assertEquals(DeviceStatus.NotReady, ioInfo._status);
    }

//    @Test
//    public void ioStart_failed_badFunction(
//    ) {
//        TestChannelModule cm = new TestChannelModule();
//        FileSystemDiskDevice d = new FileSystemDiskDevice("DISK0");
//
//        DeviceIOInfo[] ioInfos = {
//            new DeviceIOInfo.NonTransferBuilder().setSource(cm)
//                                                 .setIOFunction(IOFunction.Close)
//                                                 .build(),
//            new DeviceIOInfo.NonTransferBuilder().setSource(cm)
//                                                 .setIOFunction(IOFunction.MoveBlock)
//                                                 .build(),
//            new DeviceIOInfo.NonTransferBuilder().setSource(cm)
//                                                 .setIOFunction(IOFunction.MoveBlockBackward)
//                                                 .build(),
//            new DeviceIOInfo.NonTransferBuilder().setSource(cm)
//                                                 .setIOFunction(IOFunction.MoveFile)
//                                                 .build(),
//            new DeviceIOInfo.NonTransferBuilder().setSource(cm)
//                                                 .setIOFunction(IOFunction.MoveFileBackward)
//                                                 .build(),
//            new DeviceIOInfo.NonTransferBuilder().setSource(cm)
//                                                 .setIOFunction(IOFunction.Rewind)
//                                                 .build(),
//            new DeviceIOInfo.NonTransferBuilder().setSource(cm)
//                                                 .setIOFunction(IOFunction.RewindInterlock)
//                                                 .build(),
//            new DeviceIOInfo.NonTransferBuilder().setSource(cm)
//                                                 .setIOFunction(IOFunction.ReadBackward)
//                                                 .build(),
//            new DeviceIOInfo.NonTransferBuilder().setSource(cm)
//                                                 .setIOFunction(IOFunction.WriteEndOfFile)
//                                                 .build(),
//        };
//
//        for (DeviceIOInfo ioInfo : ioInfos) {
//            cm.submitAndWait(d, ioInfo);
//            assertEquals(DeviceStatus.InvalidFunction, ioInfo._status);
//        }
//    }

//    @Test
//    public void ioStart_none(
//    ) {
//        TestChannelModule cm = new TestChannelModule();
//        FileSystemDiskDevice d = new FileSystemDiskDevice("DISK0");
//        DeviceIOInfo ioInfo = new DeviceIOInfo.NonTransferBuilder().setSource(cm)
//                                                                   .setIOFunction(IOFunction.None)
//                                                                   .build();
//        cm.submitAndWait(d, ioInfo);
//        assertEquals(DeviceStatus.Successful, ioInfo._status);
//    }

//    @Test
//    public void ioUnload_successful(
//    ) throws IOException {
//        String fileName = getTestFileName();
//        RandomAccessFile file = new RandomAccessFile(fileName, "rw");
//        byte[] buffer = new byte[128];
//        TestDevice.ScratchPad sp = new TestDevice.ScratchPad(new PrepFactor(1792),
//                                                             new BlockSize(8192),
//                                                             new BlockCount(10000));
//        sp.serialize(ByteBuffer.wrap(buffer));
//        file.write(buffer);
//        file.close();
//
//        TestChannelModule cm = new TestChannelModule();
//        TestDevice d = new TestDevice();
//        d.mount(fileName);
//        d.setReady(true);
//        DeviceIOInfo ioInfo = new DeviceIOInfo.NonTransferBuilder().setSource(cm)
//                                                                   .setIOFunction(IOFunction.Unload)
//                                                                   .build();
//        cm.submitAndWait(d, ioInfo);
//        assertEquals(DeviceStatus.Successful, ioInfo._status);
//        d.unmount();
//        deleteTestFile(fileName);
//    }

//    @Test
//    public void ioUnload_failed_notReady(
//    ) throws IOException {
//        String fileName = getTestFileName();
//        RandomAccessFile file = new RandomAccessFile(fileName, "rw");
//        byte[] buffer = new byte[128];
//        TestDevice.ScratchPad sp = new TestDevice.ScratchPad(new PrepFactor(1792),
//                                                             new BlockSize(8192),
//                                                             new BlockCount(10000));
//        sp.serialize(ByteBuffer.wrap(buffer));
//        file.write(buffer);
//        file.close();
//
//        TestChannelModule cm = new TestChannelModule();
//        TestDevice d = new TestDevice();
//        d.mount(fileName);
//        d.setReady(false);
//        DeviceIOInfo ioInfo = new DeviceIOInfo.NonTransferBuilder().setSource(cm)
//                                                                   .setIOFunction(IOFunction.Unload)
//                                                                   .build();
//        cm.submitAndWait(d, ioInfo);
//        assertEquals(DeviceStatus.NotReady, ioInfo._status);
//
//        d.unmount();
//        deleteTestFile(fileName);
//    }

//    @Test
//    public void ioWrite_ioRead_successful(
//    ) throws Exception {
//        Random r = new Random((int)System.currentTimeMillis());
//        String fileName = getTestFileName();
//        BlockSize[] blockSizes = {
//            new BlockSize(128),
//            new BlockSize(256),
//            new BlockSize(512),
//            new BlockSize(1024),
//            new BlockSize(2048),
//            new BlockSize(4096),
//            new BlockSize(8192)
//        };
//
//        //  There is some delay per iteration - it is over a second delay during unmount().
//        //  I think this is normal and acceptable.  I think.
//        for (BlockSize blockSize : blockSizes) {
//            PrepFactor prepFactor = PrepFactor.getPrepFactorFromBlockSize(blockSize);
//            BlockCount blockCount = new BlockCount(10000 * prepFactor.getBlocksPerTrack());
//            FileSystemDiskDevice.createPack(fileName, blockSize, blockCount);
//
//            //  set up the device and eat the UA
//            TestChannelModule cm = new TestChannelModule();
//            TestDevice d = new TestDevice();
//            d.mount(fileName);
//            d.setReady(true);
//            DeviceIOInfo ioInfo = new DeviceIOInfo.ByteTransferBuilder().setSource(cm)
//                                                                        .setIOFunction(IOFunction.GetInfo)
//                                                                        .setTransferCount(128)
//                                                                        .build();
//            cm.submitAndWait(d, ioInfo);
//
//            for (int x = 0; x < 16; ++x) {
//                long blockIdVal = r.nextInt() % blockCount.getValue();
//                if (blockIdVal < 0) {
//                    blockIdVal = 0 - blockIdVal;
//                }
//
//                //  note - we purposely allow block count of zero
//                int ioBlockCount = r.nextInt() % 4;
//                if (ioBlockCount < 0) {
//                    ioBlockCount = 0 - ioBlockCount;
//                }
//
//                int bufferSize = ioBlockCount * blockSize.getValue();
//                byte[] writeBuffer = new byte[bufferSize];
//                r.nextBytes(writeBuffer);
//
//                DeviceIOInfo ioInfoWrite = new DeviceIOInfo.ByteTransferBuilder().setSource(cm)
//                                                                                 .setIOFunction(IOFunction.Write)
//                                                                                 .setBlockId(blockIdVal)
//                                                                                 .setBuffer(writeBuffer)
//                                                                                 .setTransferCount(bufferSize)
//                                                                                 .build();
//                cm.submitAndWait(d, ioInfoWrite);
//                assertEquals(DeviceStatus.Successful, ioInfoWrite._status);
//                assertEquals(bufferSize, ioInfoWrite._transferredCount);
//
//                DeviceIOInfo ioInfoRead = new DeviceIOInfo.ByteTransferBuilder().setSource(cm)
//                                                                                .setIOFunction(IOFunction.Read)
//                                                                                .setBlockId(blockIdVal)
//                                                                                .setTransferCount(bufferSize)
//                                                                                .build();
//                cm.submitAndWait(d, ioInfoRead);
//                assertEquals(DeviceStatus.Successful, ioInfoRead._status);
//                assertEquals(bufferSize, ioInfoRead._transferredCount);
//                assertArrayEquals(writeBuffer, ioInfoRead._byteBuffer);
//            }
//
//            d.unmount();
//            deleteTestFile(fileName);
//        }
//    }

//    @Test
//    public void ioWrite_fail_notReady(
//    ) {
//        TestChannelModule cm = new TestChannelModule();
//        TestDevice d = new TestDevice();
//        int bufferSize = 128;
//        long blockId = 5;
//        byte[] writeBuffer = new byte[bufferSize];
//        DeviceIOInfo ioInfoWrite = new DeviceIOInfo.ByteTransferBuilder().setSource(cm)
//                                                                         .setIOFunction(IOFunction.Write)
//                                                                         .setBlockId(blockId)
//                                                                         .setBuffer(writeBuffer)
//                                                                         .setTransferCount(writeBuffer.length)
//                                                                         .build();
//        cm.submitAndWait(d, ioInfoWrite);
//        assertEquals(DeviceStatus.NotReady, ioInfoWrite._status);
//    }

//    @Test
//    public void ioWrite_fail_bufferTooSmall(
//    ) throws Exception {
//        String fileName = getTestFileName();
//        BlockSize blockSize = new BlockSize(128);
//        PrepFactor prepFactor = PrepFactor.getPrepFactorFromBlockSize(blockSize);
//        BlockCount blockCount = new BlockCount(10000 * (prepFactor.getBlocksPerTrack()));
//        FileSystemDiskDevice.createPack(fileName, blockSize, blockCount);
//
//        TestChannelModule cm = new TestChannelModule();
//        TestDevice d = new TestDevice();
//        d.mount(fileName);
//        d.setReady(true);
//
//        DeviceIOInfo ioInfo = new DeviceIOInfo.ByteTransferBuilder().setSource(cm)
//                                                                    .setIOFunction(IOFunction.GetInfo)
//                                                                    .setTransferCount(128)
//                                                                    .build();
//        cm.submitAndWait(d, ioInfo);
//
//        byte[] writeBuffer = new byte[10];
//        long blockId = 5;
//        DeviceIOInfo ioInfoWrite = new DeviceIOInfo.ByteTransferBuilder().setSource(cm)
//                                                                         .setIOFunction(IOFunction.Write)
//                                                                         .setBlockId(blockId)
//                                                                         .setBuffer(writeBuffer)
//                                                                         .setTransferCount(blockSize.getValue())
//                                                                         .build();
//        cm.submitAndWait(d, ioInfoWrite);
//        assertEquals(DeviceStatus.BufferTooSmall, ioInfoWrite._status);
//
//        d.unmount();
//        deleteTestFile(fileName);
//    }

//    @Test
//    public void ioWrite_fail_invalidBlockSize(
//    ) throws Exception {
//        String fileName = getTestFileName();
//        BlockSize blockSize = new BlockSize(128);
//        PrepFactor prepFactor = PrepFactor.getPrepFactorFromBlockSize(blockSize);
//        BlockCount blockCount = new BlockCount(10000 * (prepFactor.getBlocksPerTrack()));
//        FileSystemDiskDevice.createPack(fileName, blockSize, blockCount);
//
//        TestChannelModule cm = new TestChannelModule();
//        TestDevice d = new TestDevice();
//        d.mount(fileName);
//        d.setReady(true);
//
//        //  Clear unit attention
//        DeviceIOInfo ioInfoGetInfo = new DeviceIOInfo.ByteTransferBuilder().setSource(cm)
//                                                                           .setIOFunction(IOFunction.GetInfo)
//                                                                           .setTransferCount(128)
//                                                                           .build();
//        cm.submitAndWait(d, ioInfoGetInfo);
//
//        byte[] readBuffer = new byte[blockSize.getValue()];
//        BlockId blockId = new BlockId(5);
//
//        DeviceIOInfo ioInfoRead = new DeviceIOInfo.ByteTransferBuilder().setSource(cm)
//                                                                        .setIOFunction(IOFunction.Write)
//                                                                        .setBlockId(blockId.getValue())
//                                                                        .setBuffer(readBuffer)
//                                                                        .setTransferCount(blockSize.getValue() - 1)
//                                                                        .build();
//        cm.submitAndWait(d, ioInfoRead);
//        assertEquals(DeviceStatus.InvalidBlockSize, ioInfoRead._status);
//        d.unmount();
//        deleteTestFile(fileName);
//    }

//    @Test
//    public void ioWrite_fail_invalidBlockId(
//    ) throws Exception {
//        String fileName = getTestFileName();
//        BlockSize blockSize = new BlockSize(128);
//        PrepFactor prepFactor = PrepFactor.getPrepFactorFromBlockSize(blockSize);
//        BlockCount blockCount = new BlockCount(10000 * (prepFactor.getBlocksPerTrack()));
//        FileSystemDiskDevice.createPack(fileName, blockSize, blockCount);
//
//        TestChannelModule cm = new TestChannelModule();
//        TestDevice d = new TestDevice();
//        d.mount(fileName);
//        d.setReady(true);
//
//        //  Clear unit attention
//        DeviceIOInfo ioInfoGetInfo = new DeviceIOInfo.ByteTransferBuilder().setSource(cm)
//                                                                           .setIOFunction(IOFunction.GetInfo)
//                                                                           .setTransferCount(128)
//                                                                           .build();
//        cm.submitAndWait(d, ioInfoGetInfo);
//
//        byte[] readBuffer = new byte[blockSize.getValue()];
//        DeviceIOInfo ioInfoRead = new DeviceIOInfo.ByteTransferBuilder().setSource(cm)
//                                                                        .setIOFunction(IOFunction.Write)
//                                                                        .setBlockId(blockCount.getValue())
//                                                                        .setBuffer(readBuffer)
//                                                                        .setTransferCount(blockSize.getValue())
//                                                                        .build();
//        cm.submitAndWait(d, ioInfoRead);
//        assertEquals(DeviceStatus.InvalidBlockId, ioInfoRead._status);
//
//        d.unmount();
//        deleteTestFile(fileName);
//    }

//    @Test
//    public void ioWrite_fail_invalidBlockCount(
//    ) throws Exception {
//        String fileName = getTestFileName();
//        BlockSize blockSize = new BlockSize(128);
//        PrepFactor prepFactor = PrepFactor.getPrepFactorFromBlockSize(blockSize);
//        BlockCount blockCount = new BlockCount(10000 * (prepFactor.getBlocksPerTrack()));
//        FileSystemDiskDevice.createPack(fileName, blockSize, blockCount);
//
//        TestChannelModule cm = new TestChannelModule();
//        TestDevice d = new TestDevice();
//        d.mount(fileName);
//        d.setReady(true);
//
//        //  Clear unit attention
//        DeviceIOInfo ioInfoGetInfo = new DeviceIOInfo.ByteTransferBuilder().setSource(cm)
//                                                                           .setIOFunction(IOFunction.GetInfo)
//                                                                           .setTransferCount(128)
//                                                                           .build();
//        cm.submitAndWait(d, ioInfoGetInfo);
//
//        byte[] readBuffer = new byte[2 * blockSize.getValue()];
//        DeviceIOInfo ioInfoRead = new DeviceIOInfo.ByteTransferBuilder().setSource(cm)
//                                                                        .setIOFunction(IOFunction.Write)
//                                                                        .setBlockId(blockCount.getValue() - 1)
//                                                                        .setBuffer(readBuffer)
//                                                                        .setTransferCount(2 * blockSize.getValue())
//                                                                        .build();
//        cm.submitAndWait(d, ioInfoRead);
//        assertEquals(DeviceStatus.InvalidBlockCount, ioInfoRead._status);
//
//        d.unmount();
//        deleteTestFile(fileName);
//    }

//    @Test
//    public void ioWrite_fail_unitAttention(
//    ) throws Exception {
//        String fileName = getTestFileName();
//        BlockSize blockSize = new BlockSize(128);
//        PrepFactor prepFactor = PrepFactor.getPrepFactorFromBlockSize(blockSize);
//        BlockCount blockCount = new BlockCount(10000 * (prepFactor.getBlocksPerTrack()));
//        FileSystemDiskDevice.createPack(fileName, blockSize, blockCount);
//
//        TestChannelModule cm = new TestChannelModule();
//        TestDevice d = new TestDevice();
//        d.mount(fileName);
//        d.setReady(true);
//
//        byte[] writeBuffer = new byte[blockSize.getValue()];
//        long blockId = 5;
//        DeviceIOInfo ioInfoWrite = new DeviceIOInfo.ByteTransferBuilder().setSource(cm)
//                                                                         .setIOFunction(IOFunction.Write)
//                                                                         .setBlockId(blockId)
//                                                                         .setBuffer(writeBuffer)
//                                                                         .setTransferCount(blockSize.getValue())
//                                                                         .build();
//        cm.submitAndWait(d, ioInfoWrite);
//        assertEquals(DeviceStatus.UnitAttention, ioInfoWrite._status);
//
//        d.unmount();
//        deleteTestFile(fileName);
//    }

//    @Test
//    public void ioWrite_fail_writeProtected(
//    ) throws Exception {
//        String fileName = getTestFileName();
//        BlockSize blockSize = new BlockSize(128);
//        PrepFactor prepFactor = PrepFactor.getPrepFactorFromBlockSize(blockSize);
//        BlockCount blockCount = new BlockCount(10000 * (prepFactor.getBlocksPerTrack()));
//        FileSystemDiskDevice.createPack(fileName, blockSize, blockCount);
//
//        TestChannelModule cm = new TestChannelModule();
//        TestDevice d = new TestDevice();
//        d.mount(fileName);
//        d.setReady(true);
//        d.setIsWriteProtected(true);
//
//        //  Clear unit attention
//        DeviceIOInfo ioInfoGetInfo = new DeviceIOInfo.ByteTransferBuilder().setSource(cm)
//                                                                           .setIOFunction(IOFunction.GetInfo)
//                                                                           .setTransferCount(128)
//                                                                           .build();
//        cm.submitAndWait(d, ioInfoGetInfo);
//
//        byte[] writeBuffer = new byte[blockSize.getValue()];
//        long blockId = 5;
//        DeviceIOInfo ioInfoWrite = new DeviceIOInfo.ByteTransferBuilder().setSource(cm)
//                                                                         .setIOFunction(IOFunction.Write)
//                                                                         .setBlockId(blockId)
//                                                                         .setBuffer(writeBuffer)
//                                                                         .setTransferCount(blockSize.getValue())
//                                                                         .build();
//        cm.submitAndWait(d, ioInfoWrite);
//        assertEquals(DeviceStatus.WriteProtected, ioInfoWrite._status);
//
//        d.unmount();
//        deleteTestFile(fileName);
//    }

//    @Test
//    public void mount_successful(
//    ) throws IOException {
//        String fileName = getTestFileName();
//        RandomAccessFile file = new RandomAccessFile(fileName, "rw");
//        byte[] buffer = new byte[128];
//        TestDevice.ScratchPad sp = new TestDevice.ScratchPad(new PrepFactor(1792),
//                                                             new BlockSize(8192),
//                                                             new BlockCount(10000));
//        sp.serialize(ByteBuffer.wrap(buffer));
//        file.write(buffer);
//        file.close();
//
//        TestDevice d = new TestDevice();
//        assertTrue(d.mount(fileName));
//        d.unmount();
//        deleteTestFile(fileName);
//    }

//    @Test
//    public void mount_successful_scratchPadWrongMinorVersion(
//    ) throws IOException {
//        String fileName = getTestFileName();
//        RandomAccessFile file = new RandomAccessFile(fileName, "rw");
//        byte[] buffer = new byte[128];
//        TestDevice.ScratchPad sp = new TestDevice.ScratchPad(new PrepFactor(1792),
//                                                             new BlockSize(8192),
//                                                             new BlockCount(10000));
//        sp._minorVersion = -1;
//        sp.serialize(ByteBuffer.wrap(buffer));
//        file.write(buffer);
//        file.close();
//
//        TestDevice d = new TestDevice();
//        assertTrue(d.mount(fileName));
//        d.unmount();
//        deleteTestFile(fileName);
//    }

//    @Test
//    public void mount_failed_alreadyMounted(
//    ) throws IOException {
//        String fileName = getTestFileName();
//        RandomAccessFile file = new RandomAccessFile(fileName, "rw");
//        byte[] buffer = new byte[128];
//        TestDevice.ScratchPad sp = new TestDevice.ScratchPad(new PrepFactor(1792),
//                                                             new BlockSize(8192),
//                                                             new BlockCount(10000));
//        sp.serialize(ByteBuffer.wrap(buffer));
//        file.write(buffer);
//        file.close();
//
//        TestDevice d = new TestDevice();
//        d.mount(fileName);
//        assertFalse(d.mount("BLAH.vol"));
//        d.unmount();
//        deleteTestFile(fileName);
//    }

    @Test
    public void mount_failed_noFile(
    ) {
        TestDevice d = new TestDevice();
        assertFalse(d.mount("/blah/blah/blah/FOO.vol"));
    }

//    @Test
//    public void mount_failed_noScratchPad(
//    ) throws IOException {
//        String fileName = getTestFileName();
//        Files.deleteIfExists(FileSystems.getDefault().getPath(fileName));
//        RandomAccessFile file = new RandomAccessFile(fileName, "rw");
//        file.close();
//        TestDevice d = new TestDevice();
//        assertFalse(d.mount(fileName));
//        deleteTestFile(fileName);
//    }

//    @Test
//    public void mount_failed_incompleteScratchPad(
//    ) throws IOException {
//        String fileName = getTestFileName();
//        Files.deleteIfExists(FileSystems.getDefault().getPath(fileName));
//        RandomAccessFile file = new RandomAccessFile(fileName, "rw");
//        byte[] buffer = { 0, 0, 0, 0 };
//        file.write(buffer);
//        file.close();
//
//        TestDevice d = new TestDevice();
//        assertFalse(d.mount(fileName));
//        deleteTestFile(fileName);
//    }

//    @Test
//    public void mount_failed_scratchPadWrongIdentifier(
//    ) throws IOException {
//        String fileName = getTestFileName();
//        RandomAccessFile file = new RandomAccessFile(fileName, "rw");
//        byte[] buffer = new byte[128];
//        TestDevice.ScratchPad sp = new TestDevice.ScratchPad(new PrepFactor(1792),
//                                                             new BlockSize(8192),
//                                                             new BlockCount(10000));
//        sp._identifier = "BadDog";
//        sp.serialize(ByteBuffer.wrap(buffer));
//        file.write(buffer);
//        file.close();
//
//        TestDevice d = new TestDevice();
//        assertFalse(d.mount(fileName));
//        deleteTestFile(fileName);
//    }

//    @Test
//    public void mount_failed_scratchPadWrongMajorVersion(
//    ) throws IOException {
//        String fileName = getTestFileName();
//        RandomAccessFile file = new RandomAccessFile(fileName, "rw");
//        byte[] buffer = new byte[128];
//        TestDevice.ScratchPad sp = new TestDevice.ScratchPad(new PrepFactor(1792),
//                                                             new BlockSize(8192),
//                                                             new BlockCount(10000));
//        sp._majorVersion = -1;
//        sp.serialize(ByteBuffer.wrap(buffer));
//        file.write(buffer);
//        file.close();
//
//        TestDevice d = new TestDevice();
//        assertFalse(d.mount(fileName));
//        deleteTestFile(fileName);
//    }

//    @Test
//    public void setReady_false_successful_alreadyFalse(
//    ) throws Exception {
//        String fileName = getTestFileName();
//        FileSystemDiskDevice.createPack(fileName, new BlockSize(8192), new BlockCount(10000));
//
//        FileSystemDiskDevice d = new TestDevice();
//        d.mount(fileName);
//        assertTrue(d.setReady(false));
//        d.unmount();
//        deleteTestFile(fileName);
//    }

//    @Test
//    public void setReady_false_successful(
//    ) throws Exception {
//        String fileName = getTestFileName();
//        FileSystemDiskDevice.createPack(fileName, new BlockSize(8192), new BlockCount(10000));
//
//        FileSystemDiskDevice d = new TestDevice();
//        d.mount(fileName);
//        d.setReady(true);
//        assertTrue(d.setReady(false));
//        d.unmount();
//        deleteTestFile(fileName);
//    }

    @Test
    public void setReady_false_successful_noReel(
    ) {
        FileSystemTapeDevice d = new FileSystemTapeDevice("TAPE0");
        assertTrue(d.setReady(false));
    }

//    @Test
//    public void setReady_true_successful(
//    ) throws Exception {
//        String fileName = getTestFileName();
//        FileSystemDiskDevice.createPack(fileName,
//                                        new BlockSize(8192),
//                                        new BlockCount(10000));
//        FileSystemDiskDevice d = new FileSystemDiskDevice("DISK0");
//        d.mount(fileName);
//        assertTrue(d.setReady(true));
//        d.unmount();
//        deleteTestFile(fileName);
//    }

//    @Test
//    public void setReady_true_successful_alreadyTrue(
//    ) throws Exception {
//        String fileName = getTestFileName();
//        FileSystemDiskDevice.createPack(fileName,
//                                        new BlockSize(8192),
//                                        new BlockCount(10000));
//        FileSystemDiskDevice d = new FileSystemDiskDevice("DISK0");
//        d.mount(fileName);
//        d.setReady(true);
//        assertTrue(d.setReady(true));
//        d.unmount();
//        deleteTestFile(fileName);
//    }

    @Test
    public void setReady_true_failed_noReel(
    ) {
        FileSystemTapeDevice d = new FileSystemTapeDevice("TAPE0");
        assertFalse(d.setReady(true));
    }

//    @Test
//    public void unmount_successful(
//    ) throws IOException {
//        String fileName = getTestFileName();
//        RandomAccessFile file = new RandomAccessFile(fileName, "rw");
//        byte[] buffer = new byte[128];
//        TestDevice.ScratchPad sp = new TestDevice.ScratchPad(new PrepFactor(1792),
//                                                             new BlockSize(8192),
//                                                             new BlockCount(10000));
//        sp.serialize(ByteBuffer.wrap(buffer));
//        file.write(buffer);
//        file.close();
//
//        TestDevice d = new TestDevice();
//        d.mount(fileName);
//        assertTrue(d.unmount());
//        deleteTestFile(fileName);
//    }

    @Test
    public void unmount_failed(
    ) {
        TestDevice d = new TestDevice();
        assertFalse(d.unmount());
    }
}
