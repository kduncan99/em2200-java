/*
 * Copyright (c) 2018-2019 by Kurt Duncan - All Rights Reserved
 */

package com.kadware.komodo.hardwarelib.functions.fixedPointBinary;

import com.kadware.komodo.baselib.InstructionWord;
import com.kadware.komodo.hardwarelib.misc.DesignatorRegister;
import com.kadware.komodo.hardwarelib.InstructionProcessor;
import com.kadware.komodo.hardwarelib.exceptions.UnresolvedAddressException;
import com.kadware.komodo.hardwarelib.interrupts.MachineInterrupt;
import com.kadware.komodo.hardwarelib.interrupts.OperationTrapInterrupt;
import com.kadware.komodo.hardwarelib.functions.InstructionHandler;

/**
 * Handles the DEC instruction f=005, a=011
 */
@SuppressWarnings("Duplicates")
public class DECFunctionHandler extends InstructionHandler {

    private static final long NEGATIVE_ONE_36 = 0_777777_777776l;

    @Override
    public void handle(
        final InstructionProcessor ip,
        final InstructionWord iw
    ) throws MachineInterrupt,
             UnresolvedAddressException {
        boolean twosComplement = chooseTwosComplementBasedOnJField(iw, ip.getDesignatorRegister());
        boolean skip = ip.incrementOperand(true, true, NEGATIVE_ONE_36, twosComplement);

        DesignatorRegister dr = ip.getDesignatorRegister();
        if (dr.getOperationTrapEnabled() && dr.getOverflow()) {
            throw new OperationTrapInterrupt(OperationTrapInterrupt.Reason.FixedPointBinaryIntegerOverflow);
        }

        if (skip) {
            ip.skipNextInstruction();
        }
    }

    @Override
    public Instruction getInstruction() { return Instruction.DEC; }
}
