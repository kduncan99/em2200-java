/*
 * Copyright (c) 2018-2019 by Kurt Duncan - All Rights Reserved
 */

package com.kadware.komodo.hardwarelib.functions.test;

import com.kadware.komodo.baselib.InstructionWord;
import com.kadware.komodo.baselib.Word36;
import com.kadware.komodo.hardwarelib.InstructionProcessor;
import com.kadware.komodo.hardwarelib.exceptions.UnresolvedAddressException;
import com.kadware.komodo.hardwarelib.functions.InstructionHandler;
import com.kadware.komodo.hardwarelib.interrupts.MachineInterrupt;

/**
 * Handles the TW instruction f=056
 */
public class TWFunctionHandler extends InstructionHandler {

    @Override
    public void handle(
        final InstructionProcessor ip,
        final InstructionWord iw
    ) throws MachineInterrupt,
             UnresolvedAddressException {
        //  Skip NI if A(a) < (U) <= A(a+1)

        long uValue = ip.getOperand(true, true, true, true);
        long aValue = ip.getExecOrUserARegister((int)iw.getA()).getW();
        long aValuePlus = ip.getExecOrUserARegister((int)iw.getA() + 1).getW();

        if ((Word36.compare(aValue, uValue) < 0) && (Word36.compare(uValue, aValuePlus) <= 0)) {
            ip.setProgramCounter(ip.getProgramAddressRegister().getProgramCounter() + 1, false);
        }
    }

    @Override
    public Instruction getInstruction() { return Instruction.TW; }
}
