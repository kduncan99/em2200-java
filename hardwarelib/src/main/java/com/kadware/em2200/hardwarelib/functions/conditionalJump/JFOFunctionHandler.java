/*
 * Copyright (c) 2018 by Kurt Duncan - All Rights Reserved
 */

package com.kadware.em2200.hardwarelib.functions.conditionalJump;

import com.kadware.em2200.baselib.InstructionWord;
import com.kadware.em2200.hardwarelib.InstructionProcessor;
import com.kadware.em2200.hardwarelib.exceptions.UnresolvedAddressException;
import com.kadware.em2200.hardwarelib.interrupts.MachineInterrupt;
import com.kadware.em2200.hardwarelib.misc.DesignatorRegister;
import com.kadware.em2200.hardwarelib.functions.*;

/**
 * Handles the JFO instruction f=074 j=014 a=02
 */
public class JFOFunctionHandler extends FunctionHandler {

    @Override
    public void handle(
        final InstructionProcessor ip,
        final InstructionWord iw
    ) throws MachineInterrupt,
             UnresolvedAddressException {
        DesignatorRegister dreg = ip.getDesignatorRegister();
        if (dreg.getCharacteristicOverflow()) {
            int counter = (int)ip.getJumpOperand();
            ip.setProgramCounter(counter, true);
        }
        dreg.setCharacteristicOverflow(false);
    }
}
