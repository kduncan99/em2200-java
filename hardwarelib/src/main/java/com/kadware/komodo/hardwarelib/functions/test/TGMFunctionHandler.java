/*
 * Copyright (c) 2018-2019 by Kurt Duncan - All Rights Reserved
 */

package com.kadware.komodo.hardwarelib.functions.test;

import com.kadware.komodo.baselib.InstructionWord;
import com.kadware.komodo.baselib.OnesComplement;
import com.kadware.komodo.hardwarelib.InstructionProcessor;
import com.kadware.komodo.hardwarelib.exceptions.UnresolvedAddressException;
import com.kadware.komodo.hardwarelib.interrupts.MachineInterrupt;
import com.kadware.komodo.hardwarelib.functions.InstructionHandler;

/**
 * Handles the TGM instruction extended mode f=033 j=013
 */
public class TGMFunctionHandler extends InstructionHandler {

    @Override
    public void handle(
        final InstructionProcessor ip,
        final InstructionWord iw
    ) throws MachineInterrupt,
             UnresolvedAddressException {
        //  Skip NI if |(U)| > A(a)

        long uValue = ip.getOperand(true, true, true, false);
        long uNative = OnesComplement.getNative36(uValue);
        if (uNative < 0) {
            uNative = 0 - uNative;
        }

        long aValue = ip.getExecOrUserARegister((int)iw.getA()).getW();
        long aNative = OnesComplement.getNative36(aValue);
        if (uNative > aNative) {
            ip.setProgramCounter(ip.getProgramAddressRegister().getProgramCounter() + 1, false);
        }
    }

    @Override
    public Instruction getInstruction() { return Instruction.TGM; }
}
