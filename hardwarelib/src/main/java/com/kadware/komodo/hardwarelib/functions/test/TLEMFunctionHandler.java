/*
 * Copyright (c) 2018-2019 by Kurt Duncan - All Rights Reserved
 */

package com.kadware.komodo.hardwarelib.functions.test;

import com.kadware.komodo.baselib.IndexRegister;
import com.kadware.komodo.baselib.InstructionWord;
import com.kadware.komodo.hardwarelib.InstructionProcessor;
import com.kadware.komodo.hardwarelib.exceptions.UnresolvedAddressException;
import com.kadware.komodo.hardwarelib.interrupts.MachineInterrupt;
import com.kadware.komodo.hardwarelib.misc.DesignatorRegister;
import com.kadware.komodo.hardwarelib.functions.InstructionHandler;

/**
 * Handles the TLEM / TNGM instruction f=047
 */
public class TLEMFunctionHandler extends InstructionHandler {

    @Override
    public void handle(
        final InstructionProcessor ip,
        final InstructionWord iw
    ) throws MachineInterrupt,
             UnresolvedAddressException {
        //  Skip NI if (U) <= X(a).mod
        //  Always increment X(a)
        //  In Basic Mode if F0.h is true (U resolution x-reg incrementation) and F0.a == F0.x, we increment only once
        //  Only H2 of (U) is compared; j-field 0, 1, and 3 produce the same results. j-field 016 and 017 produce the same results.
        //  X(0) is used for X(a) if a == 0 (contrast to F0.x == 0 -> no indexing)
        //  In Extended Mode, X(a) incrementation is always 18 bits.

        DesignatorRegister dr = ip.getDesignatorRegister();
        IndexRegister xreg = ip.getExecOrUserXRegister((int)iw.getA());
        long uValue = (ip.getOperand(true, true, true, true) & 0_777777);
        long modValue = xreg.getXM();
        if (uValue <= modValue) {
            ip.setProgramCounter(ip.getProgramAddressRegister().getProgramCounter() + 1, false);
        }

        if (!dr.getBasicModeEnabled() || (iw.getA() != iw.getX()) || (iw.getH() == 0)) {
            xreg.incrementModifier18();
        }
    }

    @Override
    public Instruction getInstruction() { return Instruction.TLEM; }
}
