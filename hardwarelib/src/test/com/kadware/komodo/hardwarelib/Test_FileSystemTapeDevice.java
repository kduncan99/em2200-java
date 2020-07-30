/*
 * Copyright (c) 2018-2020 by Kurt Duncan - All Rights Reserved
 */

package com.kadware.komodo.hardwarelib;

import com.kadware.komodo.baselib.ArraySlice;
import com.kadware.komodo.baselib.Word36;
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
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/**
 * Unit tests for FileSystemDiskDevice class
 */
public class Test_FileSystemTapeDevice {

    public static class TestChannelModule extends ChannelModule {

        private final List<Device.IOInfo> _ioList = new LinkedList<>();

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
            final Device.IOInfo deviceIoInfo
        ) {
            if (target.handleIo(deviceIoInfo)) {
                synchronized (_ioList) {
                    _ioList.add(deviceIoInfo);
                }

                synchronized (deviceIoInfo) {
                    while (deviceIoInfo._status == Device.IOStatus.InProgress) {
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
                    Iterator<Device.IOInfo> iter = _ioList.iterator();
                    while (iter.hasNext()) {
                        Device.IOInfo ioInfo = iter.next();
                        if (ioInfo._status != Device.IOStatus.InProgress) {
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
    private static final Random _random = new Random(System.currentTimeMillis());

    /**
     * Prepends the system-wide temporary path to the given base name if found
     * @return cooked path/file name
     */
    private static String getTestFileName(
    ) {
        String pathName = System.getProperty("java.io.tmpdir");
        if (pathName == null) {
            pathName = "";
        } else if (!pathName.endsWith("/")) {
            pathName += "/";
        }
        return String.format("%sTEST%04d.vol", pathName, nextFileIndex++);
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
        assertEquals(Node.NodeCategory.Device, d._category);
        assertEquals(Device.Model.FileSystemTape, d._deviceModel);
        assertEquals(Device.Type.Tape, d._deviceType);
    }

    @Test
    public void createVolume(
    ) throws Exception {
        String fileName = getTestFileName();
        FileSystemTapeDevice.createVolume(fileName);
        deleteTestFile(fileName);
    }

    @Test(expected = FileNotFoundException.class)
    public void createVolume_badPath(
    ) throws Exception {
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

        Device.IOInfo ioInfo = new Device.IOInfo.ByteTransferBuilder().setSource(cm)
                                                                      .setIOFunction(Device.IOFunction.GetInfo)
                                                                      .setTransferCount(128)
                                                                      .build();
        cm.submitAndWait(d, ioInfo);
        assertEquals(Device.IOStatus.Successful, ioInfo._status);
        ArraySlice as = new ArraySlice(new long[28]);
        as.unpack(ioInfo._byteBuffer, false);

        int flags = (int) Word36.getS1(as.get(0));
        boolean resultIsReady = (flags & 040) != 0;
        boolean resultIsMounted = (flags & 004) != 0;
        boolean resultIsWriteProtected = (flags & 002) != 0;
        Device.Model resultModel = Device.Model.getValue((int) Word36.getS2(as.get(0)));
        Device.Type resultType = Device.Type.getValue((int) Word36.getS3(as.get(0)));

        assertTrue(resultIsReady);
        assertTrue(resultIsMounted);
        assertTrue(resultIsWriteProtected);
        assertEquals(Device.Model.FileSystemTape, resultModel);
        assertEquals(Device.Type.Tape, resultType);
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
        Device.IOInfo ioInfoRead = new Device.IOInfo.ByteTransferBuilder().setSource(cm)
                                                                          .setIOFunction(Device.IOFunction.Read)
                                                                          .setBlockId(blockId)
                                                                          .setTransferCount(blockSize)
                                                                          .build();

        cm.submitAndWait(d, ioInfoRead);
        assertEquals(Device.IOStatus.NotReady, ioInfoRead._status);
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

        Device.IOInfo ioInfoRead = new Device.IOInfo.ByteTransferBuilder().setSource(cm)
                                                                          .setIOFunction(Device.IOFunction.Read)
                                                                          .setTransferCount(1024)
                                                                          .build();
        cm.submitAndWait(d, ioInfoRead);
        assertEquals(Device.IOStatus.UnitAttention, ioInfoRead._status);

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

        Device.IOInfo ioInfo = new Device.IOInfo.NonTransferBuilder().setSource(cm)
                                                                     .setIOFunction(Device.IOFunction.Reset)
                                                                     .build();
        cm.submitAndWait(d, ioInfo);
        assertEquals(Device.IOStatus.Successful, ioInfo._status);

        d.unmount();
        deleteTestFile(fileName);
    }

    @Test
    public void ioReset_failed_notReady(
    ) {
        TestChannelModule cm = new TestChannelModule();
        FileSystemTapeDevice d = new FileSystemTapeDevice("TAPE0");
        Device.IOInfo ioInfo = new Device.IOInfo.NonTransferBuilder().setSource(cm)
                                                                     .setIOFunction(Device.IOFunction.Reset)
                                                                     .build();
        cm.submitAndWait(d, ioInfo);
        assertEquals(Device.IOStatus.NotReady, ioInfo._status);
    }

    @Test
    public void ioStart_failed_badFunction(
    ) {
        TestChannelModule cm = new TestChannelModule();
        FileSystemTapeDevice d = new TestDevice();

        Device.IOInfo[] ioInfos = {
            new Device.IOInfo.NonTransferBuilder().setSource(cm)
                                                  .setIOFunction(Device.IOFunction.Close)
                                                        .build(),
        };

        for (Device.IOInfo ioInfo : ioInfos) {
            cm.submitAndWait(d, ioInfo);
            assertEquals(Device.IOStatus.InvalidFunction, ioInfo._status);
        }
    }

    @Test
    public void ioStart_none(
    ) {
        TestChannelModule cm = new TestChannelModule();
        TestDevice d = new TestDevice();
        Device.IOInfo ioInfo = new Device.IOInfo.NonTransferBuilder().setSource(cm)
                                                                     .setIOFunction(Device.IOFunction.None)
                                                                     .build();
        cm.submitAndWait(d, ioInfo);
        assertEquals(Device.IOStatus.Successful, ioInfo._status);
    }

    @Test
    public void ioUnload_successful(
    ) throws IOException {
        String fileName = getTestFileName();
        FileSystemTapeDevice.createVolume(fileName);

        TestChannelModule cm = new TestChannelModule();
        TestDevice d = new TestDevice();
        d.mount(fileName);
        d.setReady(true);

        Device.IOInfo ioInfo = new Device.IOInfo.NonTransferBuilder().setSource(cm)
                                                                     .setIOFunction(Device.IOFunction.Unload)
                                                                     .build();
        cm.submitAndWait(d, ioInfo);
        assertEquals(Device.IOStatus.Successful, ioInfo._status);
        d.unmount();
        deleteTestFile(fileName);
    }

    @Test
    public void ioUnload_failed_notReady(
    ) throws IOException {
        String fileName = getTestFileName();
        FileSystemTapeDevice.createVolume(fileName);

        TestChannelModule cm = new TestChannelModule();
        TestDevice d = new TestDevice();
        d.mount(fileName);
        d.setReady(false);

        Device.IOInfo ioInfo = new Device.IOInfo.NonTransferBuilder().setSource(cm)
                                                                     .setIOFunction(Device.IOFunction.Unload)
                                                                     .build();
        cm.submitAndWait(d, ioInfo);
        assertEquals(Device.IOStatus.NotReady, ioInfo._status);

        d.unmount();
        deleteTestFile(fileName);
    }

    @Test
    public void io_fileMarks(
    ) throws Exception {
        //  Create a volume, mount it, write three file marks, and rewind it
        String fileName = getTestFileName();
        FileSystemTapeDevice.createVolume(fileName);

        TestChannelModule cm = new TestChannelModule();
        TestDevice d = new TestDevice();
        d.mount(fileName);
        d.setReady(true);
        d.setIsWriteProtected(false);

        Device.IOInfo ioInfoGet = new Device.IOInfo.ByteTransferBuilder().setSource(cm)
                                                                         .setIOFunction(Device.IOFunction.GetInfo)
                                                                         .setTransferCount(128)
                                                                         .build();
        cm.submitAndWait(d, ioInfoGet);
        assertEquals(Device.IOStatus.Successful, ioInfoGet._status);

        Device.IOInfo ioInfoWrite = new Device.IOInfo.NonTransferBuilder().setSource(cm)
                                                                          .setIOFunction(Device.IOFunction.WriteEndOfFile)
                                                                          .build();
        cm.submitAndWait(d, ioInfoWrite);
        assertEquals(Device.IOStatus.Successful, ioInfoWrite._status);
        assertFalse(d._loadPointFlag);
        cm.submitAndWait(d, ioInfoWrite);
        assertEquals(Device.IOStatus.Successful, ioInfoWrite._status);
        cm.submitAndWait(d, ioInfoWrite);
        assertEquals(Device.IOStatus.Successful, ioInfoWrite._status);

        Device.IOInfo ioInfoRewind = new Device.IOInfo.NonTransferBuilder().setSource(cm)
                                                                           .setIOFunction(Device.IOFunction.Rewind)
                                                                           .build();
        cm.submitAndWait(d, ioInfoRewind);
        assertEquals(Device.IOStatus.Successful, ioInfoRewind._status);
        cm.submitAndWait(d, ioInfoRewind);
        assertEquals(Device.IOStatus.EndOfTape, ioInfoRewind._status);
        assertTrue(d._loadPointFlag);

        assertEquals(3, d._miscCount);  //  get info and 2x rewind
        assertEquals(0, d._readCount);
        assertEquals(0, d._readBytes);
        assertEquals(3, d._writeCount); //  3x write mark
        assertEquals(0, d._writeBytes);

        //  Try 3 forward operations - move block, move file, read.
        Device.IOInfo ioInfoMoveBlock = new Device.IOInfo.NonTransferBuilder().setSource(cm)
                                                                              .setIOFunction(Device.IOFunction.MoveBlock)
                                                                              .build();
        cm.submitAndWait(d, ioInfoMoveBlock);
        assertEquals(Device.IOStatus.FileMark, ioInfoMoveBlock._status);

        Device.IOInfo ioInfoMoveFile = new Device.IOInfo.NonTransferBuilder().setSource(cm)
                                                                             .setIOFunction(Device.IOFunction.MoveFile)
                                                                             .build();
        cm.submitAndWait(d, ioInfoMoveFile);
        assertEquals(Device.IOStatus.Successful, ioInfoMoveFile._status);

        Device.IOInfo ioInfoRead = new Device.IOInfo.ByteTransferBuilder().setSource(cm)
                                                                          .setIOFunction(Device.IOFunction.Read)
                                                                          .setTransferCount(0)
                                                                          .build();
        cm.submitAndWait(d, ioInfoRead);
        assertEquals(Device.IOStatus.FileMark, ioInfoRead._status);

        assertEquals(5, d._miscCount);      //  previous, plus 2 moves
        assertEquals(1, d._readCount);
        assertEquals(0, d._readBytes);
        assertEquals(3, d._filesExtended);
        assertEquals(0, d._blocksExtended);

        //  Try another forward operation - should get loss of position
        cm.submitAndWait(d, ioInfoRead);
        assertEquals(Device.IOStatus.LostPosition, ioInfoRead._status);
        assertEquals(2, d._readCount);

        //  Clear lost position state
        d._lostPositionFlag = false;

        //  Now try 3 backward operations - move block, move file, read.
        ioInfoMoveBlock = new Device.IOInfo.NonTransferBuilder().setSource(cm)
                                                                .setIOFunction(Device.IOFunction.MoveBlockBackward)
                                                                .build();
        cm.submitAndWait(d, ioInfoMoveBlock);
        assertEquals(Device.IOStatus.FileMark, ioInfoMoveBlock._status);

        ioInfoMoveFile = new Device.IOInfo.NonTransferBuilder().setSource(cm)
                                                               .setIOFunction(Device.IOFunction.MoveFileBackward)
                                                               .build();
        cm.submitAndWait(d, ioInfoMoveFile);
        assertEquals(Device.IOStatus.Successful, ioInfoMoveFile._status);

        ioInfoRead = new Device.IOInfo.ByteTransferBuilder().setSource(cm)
                                                            .setIOFunction(Device.IOFunction.ReadBackward)
                                                            .setTransferCount(0)
                                                            .build();
        cm.submitAndWait(d, ioInfoRead);
        assertEquals(Device.IOStatus.FileMark, ioInfoRead._status);
        assertEquals(7, d._miscCount);      //  previous, plus 2 more moves
        assertEquals(3, d._readCount);      //  previous, plus another read
        assertEquals(0, d._readBytes);
        assertEquals(6, d._filesExtended);  //  three more tape marks encountered
        assertEquals(0, d._blocksExtended);

        //  Try one more back operation - should get end-of-tape
        cm.submitAndWait(d, ioInfoRead);
        assertEquals(Device.IOStatus.EndOfTape, ioInfoRead._status);
        assertEquals(4, d._readCount);

        d.unmount();
        deleteTestFile(fileName);
    }

    @Test
    public void ioWrite_ioRead_successful(
    ) throws Exception {
        //  Create a volume, mount it, write three file marks, and rewind it
        String fileName = getTestFileName();
        FileSystemTapeDevice.createVolume(fileName);

        TestChannelModule cm = new TestChannelModule();
        TestDevice d = new TestDevice();
        d.mount(fileName);
        d.setReady(true);
        d.setIsWriteProtected(false);

        //  source data buffer
        int blockCount = 30;
        int blockSize = 4096;
        byte[] data = new byte[blockSize];
        _random.nextBytes(data);

        //  create IOInfo blocks
        Device.IOInfo ioInfoGet = new Device.IOInfo.ByteTransferBuilder().setSource(cm)
                                                                         .setIOFunction(Device.IOFunction.GetInfo)
                                                                         .setTransferCount(128)
                                                                         .build();

        Device.IOInfo ioInfoMoveBackward = new Device.IOInfo.ByteTransferBuilder().setSource(cm)
                                                                                  .setIOFunction(Device.IOFunction.MoveFileBackward)
                                                                                  .setTransferCount(0)
                                                                                  .build();

        Device.IOInfo ioInfoRead = new Device.IOInfo.ByteTransferBuilder().setSource(cm)
                                                                          .setIOFunction(Device.IOFunction.Read)
                                                                          .setTransferCount(0)
                                                                          .build();

        Device.IOInfo ioInfoReadBackward = new Device.IOInfo.ByteTransferBuilder().setSource(cm)
                                                                                  .setIOFunction(Device.IOFunction.ReadBackward)
                                                                                  .setTransferCount(0)
                                                                                  .build();

        Device.IOInfo ioInfoRewind = new Device.IOInfo.NonTransferBuilder().setSource(cm)
                                                                           .setIOFunction(Device.IOFunction.Rewind)
                                                                           .build();

        Device.IOInfo ioInfoWrite = new Device.IOInfo.ByteTransferBuilder().setSource(cm)
                                                                           .setIOFunction(Device.IOFunction.Write)
                                                                           .setBuffer(data)
                                                                           .setTransferCount(blockSize)
                                                                           .build();

        Device.IOInfo ioInfoWriteFileMark = new Device.IOInfo.NonTransferBuilder().setSource(cm)
                                                                                  .setIOFunction(Device.IOFunction.WriteEndOfFile)
                                                                                  .build();

        //  eat unit attention
        cm.submitAndWait(d, ioInfoGet);
        assertEquals(Device.IOStatus.Successful, ioInfoGet._status);

        //  write some data blocks, followed by a file mark
        for (int bx = 0; bx < blockCount; ++bx) {
            cm.submitAndWait(d, ioInfoWrite);
            assertEquals(Device.IOStatus.Successful, ioInfoWrite._status);
        }

        cm.submitAndWait(d, ioInfoWriteFileMark);
        assertEquals(Device.IOStatus.Successful, ioInfoWriteFileMark._status);
        assertEquals(1, d._miscCount);
        assertEquals(blockCount + 1, d._writeCount);
        assertEquals(blockCount * blockSize, d._writeBytes);

        //  rewind, then read until we hit end of file
        cm.submitAndWait(d, ioInfoRewind);
        assertEquals(Device.IOStatus.Successful, ioInfoRewind._status);
        assertEquals(2, d._miscCount);

        boolean done = false;
        while (!done) {
            cm.submitAndWait(d, ioInfoRead);
            assertTrue(ioInfoRead._status == Device.IOStatus.Successful
                       || ioInfoRead._status == Device.IOStatus.FileMark);
            if (ioInfoRead._status == Device.IOStatus.FileMark) {
                done = true;
            } else {
                assertArrayEquals(data, ioInfoRead._byteBuffer);
            }
        }

        assertEquals(blockCount + 1, d._readCount);
        assertEquals(blockCount * blockSize, d._readBytes);
        assertEquals(1, d._filesExtended);

        //  Move backward one file mark, then read backward until we hit end of tape
        cm.submitAndWait(d, ioInfoMoveBackward);
        assertEquals(Device.IOStatus.Successful, ioInfoMoveBackward._status);
        assertEquals(3, d._miscCount);
        assertEquals(2, d._filesExtended);

        byte[] reverseData = new byte[data.length];
        for (int sx = data.length - 1, dx = 0; dx < reverseData.length; sx--, dx++) {
            reverseData[dx] = data[sx];
        }

        done = false;
        blockCount = 0;
        while (!done) {
            cm.submitAndWait(d, ioInfoRead);
            assertTrue(ioInfoReadBackward._status == Device.IOStatus.Successful
                       || ioInfoRead._status == Device.IOStatus.FileMark);
            if (ioInfoRead._status == Device.IOStatus.FileMark) {
                done = true;
            } else {
                ++blockCount;
                assertArrayEquals(reverseData, ioInfoRead._byteBuffer);
                assertEquals(blockCount, d._blocksExtended);
            }
        }

        d.unmount();
        deleteTestFile(fileName);
    }

    @Test
    public void ioWrite_fail_notReady(
    ) {
        TestChannelModule cm = new TestChannelModule();
        TestDevice d = new TestDevice();
        int bufferSize = 128;
        byte[] writeBuffer = new byte[bufferSize];

        Device.IOInfo ioInfoWrite = new Device.IOInfo.ByteTransferBuilder().setSource(cm)
                                                                           .setIOFunction(Device.IOFunction.Write)
                                                                           .setBuffer(writeBuffer)
                                                                           .setTransferCount(writeBuffer.length)
                                                                           .build();
        cm.submitAndWait(d, ioInfoWrite);
        assertEquals(Device.IOStatus.NotReady, ioInfoWrite._status);
    }

    @Test
    public void ioWrite_fail_invalidBlockSize(
    ) throws Exception {
        String fileName = getTestFileName();
        FileSystemTapeDevice.createVolume(fileName);

        TestChannelModule cm = new TestChannelModule();
        TestDevice d = new TestDevice();
        d.mount(fileName);
        d.setReady(true);
        d.setIsWriteProtected(false);

        Device.IOInfo ioInfoGetInfo = new Device.IOInfo.ByteTransferBuilder().setSource(cm)
                                                                             .setIOFunction(Device.IOFunction.GetInfo)
                                                                             .setTransferCount(128)
                                                                             .build();
        cm.submitAndWait(d, ioInfoGetInfo);
        assertEquals(Device.IOStatus.Successful, ioInfoGetInfo._status);

        byte[] writeBuffer = new byte[d.getMaxBlockSize() + 1];
        Device.IOInfo ioInfoRead = new Device.IOInfo.ByteTransferBuilder().setSource(cm)
                                                                          .setIOFunction(Device.IOFunction.Write)
                                                                          .setBuffer(writeBuffer)
                                                                          .setTransferCount(writeBuffer.length)
                                                                          .build();
        cm.submitAndWait(d, ioInfoRead);
        assertEquals(Device.IOStatus.InvalidBlockSize, ioInfoRead._status);
        d.unmount();
        deleteTestFile(fileName);
    }

    @Test
    public void ioWrite_fail_bufferTooSmall(
    ) throws Exception {
        String fileName = getTestFileName();
        FileSystemTapeDevice.createVolume(fileName);

        TestChannelModule cm = new TestChannelModule();
        TestDevice d = new TestDevice();
        d.mount(fileName);
        d.setReady(true);
        d.setIsWriteProtected(false);

        Device.IOInfo ioInfoGetInfo = new Device.IOInfo.ByteTransferBuilder().setSource(cm)
                                                                             .setIOFunction(Device.IOFunction.GetInfo)
                                                                             .setTransferCount(128)
                                                                             .build();
        cm.submitAndWait(d, ioInfoGetInfo);
        assertEquals(Device.IOStatus.Successful, ioInfoGetInfo._status);

        byte[] writeBuffer = new byte[1024];
        Device.IOInfo ioInfoRead = new Device.IOInfo.ByteTransferBuilder().setSource(cm)
                                                                          .setIOFunction(Device.IOFunction.Write)
                                                                          .setBuffer(writeBuffer)
                                                                          .setTransferCount(2048)
                                                                          .build();
        cm.submitAndWait(d, ioInfoRead);
        assertEquals(Device.IOStatus.BufferTooSmall, ioInfoRead._status);
        d.unmount();
        deleteTestFile(fileName);
    }

    @Test
    public void ioWrite_fail_unitAttention(
    ) throws Exception {
        String fileName = getTestFileName();
        FileSystemTapeDevice.createVolume(fileName);

        TestChannelModule cm = new TestChannelModule();
        TestDevice d = new TestDevice();
        d.mount(fileName);
        d.setReady(true);

        byte[] writeBuffer = new byte[1024];
        long blockId = 5;
        Device.IOInfo ioInfoWrite = new Device.IOInfo.ByteTransferBuilder().setSource(cm)
                                                                           .setIOFunction(Device.IOFunction.Write)
                                                                           .setBlockId(blockId)
                                                                           .setBuffer(writeBuffer)
                                                                           .setTransferCount(writeBuffer.length)
                                                                           .build();
        cm.submitAndWait(d, ioInfoWrite);
        assertEquals(Device.IOStatus.UnitAttention, ioInfoWrite._status);

        d.unmount();
        deleteTestFile(fileName);
    }

    @Test
    public void ioWrite_fail_writeProtected(
    ) throws Exception {
        String fileName = getTestFileName();
        FileSystemTapeDevice.createVolume(fileName);

        TestChannelModule cm = new TestChannelModule();
        TestDevice d = new TestDevice();
        d.mount(fileName);
        d.setReady(true);
        d.setIsWriteProtected(true);

        //  Clear unit attention
        Device.IOInfo ioInfoGetInfo = new Device.IOInfo.ByteTransferBuilder().setSource(cm)
                                                                             .setIOFunction(Device.IOFunction.GetInfo)
                                                                             .setTransferCount(128)
                                                                             .build();
        cm.submitAndWait(d, ioInfoGetInfo);

        byte[] writeBuffer = new byte[1024];
        long blockId = 5;
        Device.IOInfo ioInfoWrite = new Device.IOInfo.ByteTransferBuilder().setSource(cm)
                                                                           .setIOFunction(Device.IOFunction.Write)
                                                                           .setBlockId(blockId)
                                                                           .setBuffer(writeBuffer)
                                                                           .setTransferCount(1024)
                                                                           .build();
        cm.submitAndWait(d, ioInfoWrite);
        assertEquals(Device.IOStatus.WriteProtected, ioInfoWrite._status);

        d.unmount();
        deleteTestFile(fileName);
    }

    @Test
    public void mount_successful(
    ) throws IOException {
        String fileName = getTestFileName();
        RandomAccessFile file = new RandomAccessFile(fileName, "rw");
        byte[] buffer = new byte[128];
        TestDevice.ScratchPad sp = new TestDevice.ScratchPad(FileSystemTapeDevice.MIN_FILE_SIZE);
        sp.serialize(ByteBuffer.wrap(buffer));
        file.write(buffer);
        file.close();

        TestDevice d = new TestDevice();
        assertTrue(d.mount(fileName));
        d.unmount();
        deleteTestFile(fileName);
    }

    @Test
    public void mount_successful_scratchPadWrongMinorVersion(
    ) throws IOException {
        String fileName = getTestFileName();
        RandomAccessFile file = new RandomAccessFile(fileName, "rw");
        byte[] buffer = new byte[128];
        TestDevice.ScratchPad sp = new TestDevice.ScratchPad(FileSystemTapeDevice.MIN_FILE_SIZE);
        sp._minorVersion = -1;
        sp.serialize(ByteBuffer.wrap(buffer));
        file.write(buffer);
        file.close();

        TestDevice d = new TestDevice();
        assertTrue(d.mount(fileName));
        d.unmount();
        deleteTestFile(fileName);
    }

    @Test
    public void mount_failed_alreadyMounted(
    ) throws IOException {
        String fileName = getTestFileName();
        RandomAccessFile file = new RandomAccessFile(fileName, "rw");
        byte[] buffer = new byte[128];
        TestDevice.ScratchPad sp = new TestDevice.ScratchPad(FileSystemTapeDevice.MIN_FILE_SIZE);
        sp.serialize(ByteBuffer.wrap(buffer));
        file.write(buffer);
        file.close();

        TestDevice d = new TestDevice();
        d.mount(fileName);
        assertFalse(d.mount("BLAH.vol"));
        d.unmount();
        deleteTestFile(fileName);
    }

    @Test
    public void mount_failed_noFile(
    ) {
        TestDevice d = new TestDevice();
        assertFalse(d.mount("/blah/blah/blah/FOO.vol"));
    }

    @Test
    public void mount_failed_noScratchPad(
    ) throws IOException {
        String fileName = getTestFileName();
        Files.deleteIfExists(FileSystems.getDefault().getPath(fileName));
        RandomAccessFile file = new RandomAccessFile(fileName, "rw");
        file.close();
        TestDevice d = new TestDevice();
        assertFalse(d.mount(fileName));
        deleteTestFile(fileName);
    }

    @Test
    public void mount_failed_incompleteScratchPad(
    ) throws IOException {
        String fileName = getTestFileName();
        Files.deleteIfExists(FileSystems.getDefault().getPath(fileName));
        RandomAccessFile file = new RandomAccessFile(fileName, "rw");
        byte[] buffer = { 0, 0, 0, 0 };
        file.write(buffer);
        file.close();

        TestDevice d = new TestDevice();
        assertFalse(d.mount(fileName));
        deleteTestFile(fileName);
    }

    @Test
    public void mount_failed_scratchPadWrongIdentifier(
    ) throws IOException {
        String fileName = getTestFileName();
        RandomAccessFile file = new RandomAccessFile(fileName, "rw");
        byte[] buffer = new byte[128];
        TestDevice.ScratchPad sp = new TestDevice.ScratchPad(FileSystemTapeDevice.MIN_FILE_SIZE);
        sp._identifier = "BadDog";
        sp.serialize(ByteBuffer.wrap(buffer));
        file.write(buffer);
        file.close();

        TestDevice d = new TestDevice();
        assertFalse(d.mount(fileName));
        deleteTestFile(fileName);
    }

    @Test
    public void mount_failed_scratchPadWrongMajorVersion(
    ) throws IOException {
        String fileName = getTestFileName();
        RandomAccessFile file = new RandomAccessFile(fileName, "rw");
        byte[] buffer = new byte[128];
        TestDevice.ScratchPad sp = new TestDevice.ScratchPad(FileSystemTapeDevice.MIN_FILE_SIZE);
        sp._majorVersion = -1;
        sp.serialize(ByteBuffer.wrap(buffer));
        file.write(buffer);
        file.close();

        TestDevice d = new TestDevice();
        assertFalse(d.mount(fileName));
        deleteTestFile(fileName);
    }

    @Test
    public void setReady_false_successful_alreadyFalse(
    ) throws Exception {
        String fileName = getTestFileName();
        FileSystemTapeDevice.createVolume(fileName, FileSystemTapeDevice.MIN_FILE_SIZE);

        TestDevice d = new TestDevice();
        d.mount(fileName);
        assertTrue(d.setReady(false));
        d.unmount();
        deleteTestFile(fileName);
    }

    @Test
    public void setReady_false_successful(
    ) throws Exception {
        String fileName = getTestFileName();
        FileSystemTapeDevice.createVolume(fileName, FileSystemTapeDevice.MIN_FILE_SIZE);

        TestDevice d = new TestDevice();
        d.mount(fileName);
        d.setReady(true);
        assertTrue(d.setReady(false));
        d.unmount();
        deleteTestFile(fileName);
    }

    @Test
    public void setReady_false_successful_noReel(
    ) {
        FileSystemTapeDevice d = new FileSystemTapeDevice("TAPE0");
        assertTrue(d.setReady(false));
    }

    @Test
    public void setReady_true_successful(
    ) throws Exception {
        String fileName = getTestFileName();
        FileSystemTapeDevice.createVolume(fileName, FileSystemTapeDevice.MIN_FILE_SIZE);

        TestDevice d = new TestDevice();
        d.mount(fileName);
        assertTrue(d.setReady(true));
        d.unmount();
        deleteTestFile(fileName);
    }

    @Test
    public void setReady_true_successful_alreadyTrue(
    ) throws Exception {
        String fileName = getTestFileName();
        FileSystemTapeDevice.createVolume(fileName, FileSystemTapeDevice.MIN_FILE_SIZE);

        TestDevice d = new TestDevice();
        d.mount(fileName);
        d.setReady(true);
        assertTrue(d.setReady(true));
        d.unmount();
        deleteTestFile(fileName);
    }

    @Test
    public void setReady_true_failed_noReel(
    ) {
        FileSystemTapeDevice d = new FileSystemTapeDevice("TAPE0");
        assertFalse(d.setReady(true));
    }

    @Test
    public void unmount_successful(
    ) throws IOException {
        String fileName = getTestFileName();
        RandomAccessFile file = new RandomAccessFile(fileName, "rw");
        byte[] buffer = new byte[128];
        TestDevice.ScratchPad sp = new TestDevice.ScratchPad(FileSystemTapeDevice.MIN_FILE_SIZE);
        sp.serialize(ByteBuffer.wrap(buffer));
        file.write(buffer);
        file.close();

        TestDevice d = new TestDevice();
        d.mount(fileName);
        assertTrue(d.unmount());
        deleteTestFile(fileName);
    }

    @Test
    public void unmount_failed(
    ) {
        TestDevice d = new TestDevice();
        assertFalse(d.unmount());
    }
}
