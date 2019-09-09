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
 * Handles the MTNW instruction extended mode f=071 j=05
 */
@SuppressWarnings("Duplicates")
public class MTNWFunctionHandler extends InstructionHandler {

    @Override
    public void handle(
        final InstructionProcessor ip,
        final InstructionWord iw
    ) throws MachineInterrupt,
             UnresolvedAddressException {
        //  Skip NI if (A(a) AND R2) >= ((U) AND R2)
        //      or ((U) AND R2) > (A(a+1) AND R2)

        long uValue = ip.getOperand(true, true, false, false);
        long aValueLow = ip.getExecOrUserARegister((int)iw.getA()).getW();
        long aValueHigh = ip.getExecOrUserARegister((int)iw.getA() + 1).getW();
        long opMask = ip.getExecOrUserRRegister(2).getW();

        long maskedU = uValue & opMask;
        long maskedALow = aValueLow & opMask;
        long maskedAHigh = aValueHigh & opMask;

        if ((Word36.compare(maskedALow, maskedU) >= 0)
            || (Word36.compare(maskedU, maskedAHigh) > 0)) {
            ip.setProgramCounter(ip.getProgramAddressRegister().getProgramCounter() + 1, false);
        }
    }

    @Override
    public Instruction getInstruction() { return Instruction.MTNW; }
}
