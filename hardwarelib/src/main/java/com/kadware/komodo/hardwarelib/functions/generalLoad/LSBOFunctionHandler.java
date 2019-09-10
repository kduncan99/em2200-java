/*
 * Copyright (c) 2018-2019 by Kurt Duncan - All Rights Reserved
 */

package com.kadware.komodo.hardwarelib.functions.generalLoad;

import com.kadware.komodo.baselib.IndexRegister;
import com.kadware.komodo.baselib.InstructionWord;
import com.kadware.komodo.baselib.Word36;
import com.kadware.komodo.hardwarelib.InstructionProcessor;
import com.kadware.komodo.hardwarelib.exceptions.UnresolvedAddressException;
import com.kadware.komodo.hardwarelib.interrupts.MachineInterrupt;
import com.kadware.komodo.hardwarelib.functions.InstructionHandler;

/**
 * Handles the LSBO instruction f=060, extended mode only
 */
public class LSBOFunctionHandler extends InstructionHandler {

    @Override
    public void handle(
        final InstructionProcessor ip,
        final InstructionWord iw
    ) throws MachineInterrupt,
             UnresolvedAddressException {
        long operand = ip.getOperand(true, true, true, true);
        IndexRegister ixReg = ip.getExecOrUserXRegister((int) iw.getA());
        ip.setExecOrUserXRegister((int) iw.getA(), Word36.setS1(ixReg.getW(), operand));
    }

    @Override
    public Instruction getInstruction() { return Instruction.LSBO; }
}
