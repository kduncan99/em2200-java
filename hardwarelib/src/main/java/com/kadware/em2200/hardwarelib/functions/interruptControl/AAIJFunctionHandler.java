/*
 * Copyright (c) 2019 by Kurt Duncan - All Rights Reserved
 */

package com.kadware.em2200.hardwarelib.functions.interruptControl;

import com.kadware.em2200.baselib.InstructionWord;
import com.kadware.em2200.hardwarelib.InstructionProcessor;
import com.kadware.em2200.hardwarelib.exceptions.UnresolvedAddressException;
import com.kadware.em2200.hardwarelib.functions.FunctionHandler;
import com.kadware.em2200.hardwarelib.interrupts.MachineInterrupt;
import com.kadware.em2200.hardwarelib.interrupts.SignalInterrupt;

/**
 * Handles the AAIJ instruction f=074 j=014 a=06 for extended mode,
 *                              f=074 j=07  a=not-used for basic mode
 */
public class AAIJFunctionHandler extends FunctionHandler {

    @Override
    public void handle(
        final InstructionProcessor ip,
        final InstructionWord iw
    ) throws MachineInterrupt,
             UnresolvedAddressException {
        ip.getDesignatorRegister().setDeferrableInterruptEnabled(true);
        int counter = (int)ip.getJumpOperand();
        ip.setProgramCounter(counter, true);
    }
}
