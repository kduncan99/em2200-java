/*
 * Copyright (c) 2019 by Kurt Duncan - All Rights Reserved
 */

package com.kadware.em2200.hardwarelib.functions.procedureControl;

import com.kadware.em2200.baselib.IndexRegister;
import com.kadware.em2200.baselib.InstructionWord;
import com.kadware.em2200.hardwarelib.InstructionProcessor;
import com.kadware.em2200.hardwarelib.exceptions.UnresolvedAddressException;
import com.kadware.em2200.hardwarelib.functions.FunctionHandler;
import com.kadware.em2200.hardwarelib.interrupts.MachineInterrupt;

/**
 * Handles the LOCL instruction f=07 j=016 a=00
 */
public class LOCLFunctionHandler extends FunctionHandler {

    @Override
    public void handle(
        final InstructionProcessor ip,
        final InstructionWord iw
    ) throws MachineInterrupt,
             UnresolvedAddressException {
        ip.rcsPush(0);
        IndexRegister xReg = ip.getExecOrUserXRegister(0);
        xReg.setH1(ip.getDesignatorRegister().getBasicModeEnabled() ? 0_400000_000000L : 0);
        xReg.setH2(ip.getIndicatorKeyRegister().getAccessInfo().get());
        int counter = (int)ip.getJumpOperand();
        ip.setProgramCounter(counter, true);
    }
}
