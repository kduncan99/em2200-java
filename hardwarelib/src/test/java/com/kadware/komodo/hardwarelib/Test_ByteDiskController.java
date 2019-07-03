/*
 * Copyright (c) 2018 by Kurt Duncan - All Rights Reserved
 */

package com.kadware.komodo.hardwarelib;

import static org.junit.Assert.*;
import org.junit.*;

/**
 * Unit tests for ByteDiskController class
 */
public class Test_ByteDiskController {

    @Test
    public void create(
    ) {
        ByteDiskController c = new ByteDiskController("DSKCTL", (short)25);
        Assert.assertEquals(Node.Category.Controller, c.getCategory());
        assertEquals("DSKCTL", c.getName());
        Assert.assertEquals(Controller.ControllerType.ByteDisk, c.getControllerType());
        assertEquals(25, c.getSubsystemIdentifier());
    }

    @Test
    public void canConnect_success(
    ) {
        // can connect only to byte channel module
        TapeController c = new TapeController("TAPCUA", (short)0);
        //????assertTrue(c.canConnect(new SoftwareByteChannelModule("CM1-0")));
    }

    @Test
    public void canConnect_failure(
    ) throws IllegalAccessException,
             InstantiationException,
             NoSuchMethodException {
        TapeController c = new TapeController("TAPCUA", (short)0);
        assertFalse(c.canConnect(new FileSystemDiskDevice("DISK0", (short)0)));
        assertFalse(c.canConnect(new FileSystemTapeDevice("TAPE0", (short)0)));
        assertFalse(c.canConnect(new ByteDiskController("DSKCTL", (short)0)));
        assertFalse(c.canConnect(new WordDiskController("DSKCTL", (short)0)));
        assertFalse(c.canConnect(new TapeController("TAPCUB", (short)0)));
        //????assertFalse(c.canConnect(new SoftwareWordChannelModule("CM1-1")));
        assertFalse(c.canConnect(new MainStorageProcessor("MSP0",
                                                          InventoryManager.FIRST_MAIN_STORAGE_PROCESSOR_UPI,
                                                          InventoryManager.MAIN_STORAGE_PROCESSOR_SIZE)));
        assertFalse(c.canConnect(new InputOutputProcessor("IOP0", InventoryManager.FIRST_INPUT_OUTPUT_PROCESSOR_UPI)));
        assertFalse(c.canConnect(new InstructionProcessor("IP0", InventoryManager.FIRST_INSTRUCTION_PROCESSOR_UPI)));
    }
}
