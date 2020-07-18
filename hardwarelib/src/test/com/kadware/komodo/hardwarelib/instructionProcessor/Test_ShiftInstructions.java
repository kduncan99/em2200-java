/*
 * Copyright (c) 2018-2020 by Kurt Duncan - All Rights Reserved
 */

package com.kadware.komodo.hardwarelib.instructionProcessor;

import com.kadware.komodo.baselib.exceptions.BinaryLoadException;
import com.kadware.komodo.hardwarelib.InstructionProcessor;
import com.kadware.komodo.hardwarelib.exceptions.CannotConnectException;
import com.kadware.komodo.hardwarelib.exceptions.MaxNodesException;
import com.kadware.komodo.hardwarelib.exceptions.NodeNameConflictException;
import com.kadware.komodo.hardwarelib.exceptions.UPIConflictException;
import com.kadware.komodo.hardwarelib.exceptions.UPINotAssignedException;
import com.kadware.komodo.hardwarelib.exceptions.UPIProcessorTypeException;
import com.kadware.komodo.hardwarelib.interrupts.MachineInterrupt;
import com.kadware.komodo.baselib.GeneralRegisterSet;
import org.junit.*;
import static org.junit.Assert.*;

/**
 * Unit tests for InstructionProcessor class
 */
public class Test_ShiftInstructions extends BaseFunctions {

    @After
    public void after(
    ) throws UPINotAssignedException {
        clear();
    }

    @Test
    public void singleShiftAlgebraic(
    ) throws BinaryLoadException,
             CannotConnectException,
             MachineInterrupt,
             MaxNodesException,
             NodeNameConflictException,
             UPIConflictException,
             UPINotAssignedException,
             UPIProcessorTypeException {
        String[] source = {
            "          $EXTEND",
            "          $INFO 1 3",
            "          $INFO 10 1",
            "",
            "$(1)      $LIT",
            "START",
            "          LD        (0)",
            "          LA        A5,(0777777771352)",
            "          SSA       A5,6",
            "          HALT      0",
            "",
            "          $END      START"
        };

        buildSimple(source);
        createProcessors();
        ipl(true);

        assertEquals(InstructionProcessor.StopReason.Debug, _instructionProcessor.getLatestStopReason());
        assertEquals(0, _instructionProcessor.getLatestStopDetail());
        assertEquals(0_777777_777713L, _instructionProcessor.getGeneralRegister(GeneralRegisterSet.A5).getW());
    }

    @Test
    public void doubleShiftAlgebraic(
    ) throws BinaryLoadException,
             CannotConnectException,
             MachineInterrupt,
             MaxNodesException,
             NodeNameConflictException,
             UPIConflictException,
             UPINotAssignedException,
             UPIProcessorTypeException {
        String[] source = {
            "          $EXTEND",
            "          $INFO 1 3",
            "          $INFO 10 1",
            "",
            "$(1)      $LIT",
            "START",
            "          LD        (0)",
            "          LA        A2,(0333444555666)",
            "          LA        A3,(0777000111222)",
            "          DSA       A2,12",
            "          HALT      0",
            "",
            "          $END      START"
        };

        buildSimple(source);
        createProcessors();
        ipl(true);

        assertEquals(InstructionProcessor.StopReason.Debug, _instructionProcessor.getLatestStopReason());
        assertEquals(0, _instructionProcessor.getLatestStopDetail());
        assertEquals(0_000033_344455L, _instructionProcessor.getGeneralRegister(GeneralRegisterSet.A2).getW());
        assertEquals(0_566677_700011L, _instructionProcessor.getGeneralRegister(GeneralRegisterSet.A3).getW());
    }

    @Test
    public void singleShiftCircular(
    ) throws BinaryLoadException,
             CannotConnectException,
             MachineInterrupt,
             MaxNodesException,
             NodeNameConflictException,
             UPIConflictException,
             UPINotAssignedException,
             UPIProcessorTypeException {
        String[] source = {
            "          $EXTEND",
            "          $INFO 1 3",
            "          $INFO 10 1",
            "",
            "$(1)      $LIT",
            "START",
            "          LD        (0)",
            "          LA,U      A6,012345",
            "          SSC       A6,014",
            "          LA,U      A0,017",
            "          SSC       A6,3,A0",
            "          HALT      0",
            "",
            "          $END      START"
        };

        buildSimple(source);
        createProcessors();
        ipl(true);

        assertEquals(InstructionProcessor.StopReason.Debug, _instructionProcessor.getLatestStopReason());
        assertEquals(0, _instructionProcessor.getLatestStopDetail());
        assertEquals(0_000001_234500L, _instructionProcessor.getGeneralRegister(GeneralRegisterSet.A6).getW());
    }

    @Test
    public void doubleShiftCircular(
    ) throws BinaryLoadException,
             CannotConnectException,
             MachineInterrupt,
             MaxNodesException,
             NodeNameConflictException,
             UPIConflictException,
             UPINotAssignedException,
             UPIProcessorTypeException {
        String[] source = {
            "          $EXTEND",
            "          $INFO 1 3",
            "          $INFO 10 1",
            "",
            "$(1)      $LIT",
            "START",
            "          LD        (0)",
            "          LA        A2,(0)",
            "          LA        A3,(0123456,0765432)",
            "          DSC       A2,024",
            "          HALT      0",
            "",
            "          $END      START"
        };

        buildSimple(source);
        createProcessors();
        ipl(true);

        assertEquals(InstructionProcessor.StopReason.Debug, _instructionProcessor.getLatestStopReason());
        assertEquals(0, _instructionProcessor.getLatestStopDetail());
        assertEquals(0_575306_400000L, _instructionProcessor.getGeneralRegister(GeneralRegisterSet.A2).getW());
        assertEquals(0_000000_024713L, _instructionProcessor.getGeneralRegister(GeneralRegisterSet.A3).getW());
    }

    @Test
    public void singleShiftLogical(
    ) throws BinaryLoadException,
             CannotConnectException,
             MachineInterrupt,
             MaxNodesException,
             NodeNameConflictException,
             UPIConflictException,
             UPINotAssignedException,
             UPIProcessorTypeException {
        String[] source = {
            "          $EXTEND",
            "          $INFO 1 3",
            "          $INFO 10 1",
            "",
            "$(1)      $LIT",
            "START",
            "          LD        (0)",
            "          LA        A5,(0123,0366157)",
            "          SSL       A5,2",
            "          HALT      0",
            "",
            "          $END      START"
        };

        buildSimple(source);
        createProcessors();
        ipl(true);

        assertEquals(InstructionProcessor.StopReason.Debug, _instructionProcessor.getLatestStopReason());
        assertEquals(0, _instructionProcessor.getLatestStopDetail());
        assertEquals(0_000024_675433L, _instructionProcessor.getGeneralRegister(GeneralRegisterSet.A5).getW());
    }

    @Test
    public void doubleShiftLogical(
    ) throws BinaryLoadException,
             CannotConnectException,
             MachineInterrupt,
             MaxNodesException,
             NodeNameConflictException,
             UPIConflictException,
             UPINotAssignedException,
             UPIProcessorTypeException {
        String[] source = {
            "          $EXTEND",
            "          $INFO 1 3",
            "          $INFO 10 1",
            "",
            "$(1)      $LIT",
            "START",
            "          LD        (0)",
            "          LA        A2,(0777666555444)",
            "          LA        A3,(0333222112345)",
            "          DSL       A2,9",
            "          HALT      0",
            "",
            "          $END      START"
        };

        buildSimple(source);
        createProcessors();
        ipl(true);

        assertEquals(InstructionProcessor.StopReason.Debug, _instructionProcessor.getLatestStopReason());
        assertEquals(0, _instructionProcessor.getLatestStopDetail());
        assertEquals(0_000777_666555L, _instructionProcessor.getGeneralRegister(GeneralRegisterSet.A2).getW());
        assertEquals(0_444333_222112L, _instructionProcessor.getGeneralRegister(GeneralRegisterSet.A3).getW());
    }

    @Test
    public void loadShiftAndCount(
    ) throws BinaryLoadException,
             CannotConnectException,
             MachineInterrupt,
             MaxNodesException,
             NodeNameConflictException,
             UPIConflictException,
             UPINotAssignedException,
             UPIProcessorTypeException {
        String[] source = {
            "          $EXTEND",
            "          $INFO 1 3",
            "          $INFO 10 1",
            "",
            "$(0),DATA",
            "          + 0400000,0",
            "          + 0377777,0777777",
            "          + 0",
            "          + 0777777,0777777",
            "          + 0001111,0111111",
            "",
            "$(1)      $LIT",
            "START",
            "          LD        (0)",
            "          LBU       B2,(LBDIREF$+DATA,0)",
            "          LSC       A0,DATA,,B2",
            "          LSC       A2,DATA+1,,B2",
            "          LSC       A4,DATA+2,,B2",
            "          LSC       A6,DATA+3,,B2",
            "          LSC       A8,DATA+4,,B2",
            "          HALT      0",
            "",
            "          $END      START"
        };

        buildSimple(source);
        createProcessors();
        ipl(true);

        assertEquals(InstructionProcessor.StopReason.Debug, _instructionProcessor.getLatestStopReason());
        assertEquals(0, _instructionProcessor.getLatestStopDetail());
        assertEquals(0_400000_000000L, _instructionProcessor.getGeneralRegister(GeneralRegisterSet.A0).getW());
        assertEquals(0, _instructionProcessor.getGeneralRegister(GeneralRegisterSet.A1).getW());
        assertEquals(0_377777_777777L, _instructionProcessor.getGeneralRegister(GeneralRegisterSet.A2).getW());
        assertEquals(0, _instructionProcessor.getGeneralRegister(GeneralRegisterSet.A3).getW());
        assertEquals(0_000000_000000L, _instructionProcessor.getGeneralRegister(GeneralRegisterSet.A4).getW());
        assertEquals(35, _instructionProcessor.getGeneralRegister(GeneralRegisterSet.A5).getW());
        assertEquals(0_777777_777777L, _instructionProcessor.getGeneralRegister(GeneralRegisterSet.A6).getW());
        assertEquals(35, _instructionProcessor.getGeneralRegister(GeneralRegisterSet.A7).getW());
        assertEquals(0_222222_222200L, _instructionProcessor.getGeneralRegister(GeneralRegisterSet.A8).getW());
        assertEquals(7, _instructionProcessor.getGeneralRegister(GeneralRegisterSet.A9).getW());
    }

    @Test
    public void doubleLoadShiftAndCount(
    ) throws BinaryLoadException,
             CannotConnectException,
             MachineInterrupt,
             MaxNodesException,
             NodeNameConflictException,
             UPIConflictException,
             UPINotAssignedException,
             UPIProcessorTypeException {
        String[] source = {
            "          $EXTEND",
            "          $INFO 1 3",
            "          $INFO 10 1",
            "",
            "$(0),DATA",
            "          + 0",
            "          + 05432107",
            "          + 0377777,0777777",
            "          + 0777777,0777777",
            "          + 0",
            "          + 0",
            "          + 0777777,0777777",
            "          + 0777777,0777777",
            "          + 0",
            "          + 0333444,0555666",
            "",
            "$(1)      $LIT",
            "START",
            "          LD        (0)",
            "          LBU       B2,(LBDIREF$+DATA,0)",
            "          DLSC      A0,DATA,,B2",
            "          DLSC      A3,DATA+2,,B2",
            "          DLSC      A6,DATA+4,,B2",
            "          DLSC      A9,DATA+6,,B2",
            "          DLSC      A12,DATA+8,,B2",
            "          HALT      0",
            "",
            "          $END      START"
        };

        buildSimple(source);
        createProcessors();
        ipl(true);

        assertEquals(InstructionProcessor.StopReason.Debug, _instructionProcessor.getLatestStopReason());
        assertEquals(0,_instructionProcessor.getLatestStopDetail());
        assertEquals(0_261504_340000L, _instructionProcessor.getGeneralRegister(GeneralRegisterSet.A0).getW());
        assertEquals(0, _instructionProcessor.getGeneralRegister(GeneralRegisterSet.A1).getW());
        assertEquals(062, _instructionProcessor.getGeneralRegister(GeneralRegisterSet.A2).getW());
        assertEquals(0_377777_777777L, _instructionProcessor.getGeneralRegister(GeneralRegisterSet.A3).getW());
        assertEquals(0_777777_777777L, _instructionProcessor.getGeneralRegister(GeneralRegisterSet.A4).getW());
        assertEquals(0, _instructionProcessor.getGeneralRegister(GeneralRegisterSet.A5).getW());
        assertEquals(0_000000_000000L, _instructionProcessor.getGeneralRegister(GeneralRegisterSet.A6).getW());
        assertEquals(0_000000_000000L, _instructionProcessor.getGeneralRegister(GeneralRegisterSet.A7).getW());
        assertEquals(71, _instructionProcessor.getGeneralRegister(GeneralRegisterSet.A8).getW());
        assertEquals(0_777777_777777L, _instructionProcessor.getGeneralRegister(GeneralRegisterSet.A9).getW());
        assertEquals(0_777777_777777L, _instructionProcessor.getGeneralRegister(GeneralRegisterSet.A10).getW());
        assertEquals(71, _instructionProcessor.getGeneralRegister(GeneralRegisterSet.A11).getW());
        assertEquals(0_333444_555666L, _instructionProcessor.getGeneralRegister(GeneralRegisterSet.A12).getW());
        assertEquals(0_000000_000000L, _instructionProcessor.getGeneralRegister(GeneralRegisterSet.A13).getW());
        assertEquals(36, _instructionProcessor.getGeneralRegister(GeneralRegisterSet.A14).getW());
    }

    @Test
    public void leftSingleShiftCircular(
    ) throws BinaryLoadException,
             CannotConnectException,
             MachineInterrupt,
             MaxNodesException,
             NodeNameConflictException,
             UPIConflictException,
             UPINotAssignedException,
             UPIProcessorTypeException {
        String[] source = {
            "          $EXTEND",
            "          $INFO 1 3",
            "          $INFO 10 1",
            "",
            "$(1)      $LIT",
            "START",
            "          LD        (0)",
            "          LA        A5,(0776655443322)",
            "          LSSC      A5,15",
            "          HALT      0",
            "",
            "          $END      START"
        };

        buildSimple(source);
        createProcessors();
        ipl(true);

        assertEquals(InstructionProcessor.StopReason.Debug, _instructionProcessor.getLatestStopReason());
        assertEquals(0, _instructionProcessor.getLatestStopDetail());
        assertEquals(0_544332_277665L, _instructionProcessor.getGeneralRegister(GeneralRegisterSet.A5).getW());
    }

    @Test
    public void leftDoubleShiftCircular(
    ) throws BinaryLoadException,
             CannotConnectException,
             MachineInterrupt,
             MaxNodesException,
             NodeNameConflictException,
             UPIConflictException,
             UPINotAssignedException,
             UPIProcessorTypeException {
        String[] source = {
            "          $EXTEND",
            "          $INFO 1 3",
            "          $INFO 10 1",
            "",
            "$(0),DATA",
            "          + 0777777600000",
            "          + 0666666666666",
            "",
            "$(1)      $LIT",
            "START",
            "          LD        (0)",
            "          LBU       B2,(LBDIREF$+DATA,0)",
            "          DL        A2,DATA,,B2",
            "          LDSC      A2,20",
            "          HALT      0",
            "",
            "          $END      START"
        };

        buildSimple(source);
        createProcessors();
        ipl(true);

        assertEquals(InstructionProcessor.StopReason.Debug, _instructionProcessor.getLatestStopReason());
        assertEquals(0, _instructionProcessor.getLatestStopDetail());
        assertEquals(0_000003_333333L, _instructionProcessor.getGeneralRegister(GeneralRegisterSet.A2).getW());
        assertEquals(0_333333_777777L, _instructionProcessor.getGeneralRegister(GeneralRegisterSet.A3).getW());
    }

    @Test
    public void leftSingleShiftLogical(
    ) throws BinaryLoadException,
             CannotConnectException,
             MachineInterrupt,
             MaxNodesException,
             NodeNameConflictException,
             UPIConflictException,
             UPINotAssignedException,
             UPIProcessorTypeException {
        String[] source = {
            "          $EXTEND",
            "          $INFO 1 3",
            "          $INFO 10 1",
            "",
            "$(0),DATA",
            "          + 0000123366157",
            "",
            "$(1)      $LIT",
            "START",
            "          LD        (0)",
            "          LBU       B2,(LBDIREF$+DATA,0)",
            "          LA        A5,DATA,,B2",
            "          LSSL      A5,3",
            "          HALT      0",
            "",
            "          $END      START"
        };

        buildSimple(source);
        createProcessors();
        ipl(true);

        assertEquals(InstructionProcessor.StopReason.Debug, _instructionProcessor.getLatestStopReason());
        assertEquals(0, _instructionProcessor.getLatestStopDetail());
        assertEquals(0_001233_661570L, _instructionProcessor.getGeneralRegister(GeneralRegisterSet.A5).getW());
    }

    @Test
    public void leftDoubleShiftLogical(
    ) throws BinaryLoadException,
             CannotConnectException,
             MachineInterrupt,
             MaxNodesException,
             NodeNameConflictException,
             UPIConflictException,
             UPINotAssignedException,
             UPIProcessorTypeException {
        String[] source = {
            "          $EXTEND",
            "          $INFO 1 3",
            "          $INFO 10 1",
            "",
            "$(0),DATA",
            "          + 0777666,0555444",
            "          + 0333222,0112345",
            "",
            "$(1)      $LIT",
            "START",
            "          LD        (0)",
            "          LBU       B2,(LBDIREF$+DATA,0)",
            "          DL        A2,DATA,,B2",
            "          LDSL      A2,9",
            "          HALT      0",
            "",
            "          $END      START"
        };

        buildSimple(source);
        createProcessors();
        ipl(true);

        assertEquals(InstructionProcessor.StopReason.Debug, _instructionProcessor.getLatestStopReason());
        assertEquals(0_666555_444333L, _instructionProcessor.getGeneralRegister(GeneralRegisterSet.A2).getW());
        assertEquals(0_222112_345000L, _instructionProcessor.getGeneralRegister(GeneralRegisterSet.A3).getW());
    }
}
