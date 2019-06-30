/*
 * Copyright (c) 2018-2019 by Kurt Duncan - All Rights Reserved
 */

package com.kadware.komodo.hardwarelib.functions.test;

import com.kadware.komodo.baselib.InstructionWord;
import com.kadware.komodo.hardwarelib.InstructionProcessor;
import com.kadware.komodo.hardwarelib.exceptions.UnresolvedAddressException;
import com.kadware.komodo.hardwarelib.functions.InstructionHandler;
import com.kadware.komodo.hardwarelib.interrupts.MachineInterrupt;
import com.kadware.komodo.hardwarelib.interrupts.TestAndSetInterrupt;

/**
 * Handles the TCS instruction f=073 j=017 a=02
 */
public class TCSFunctionHandler extends InstructionHandler {

    @Override
    public void handle(
        final InstructionProcessor ip,
        final InstructionWord iw
    ) throws MachineInterrupt,
             UnresolvedAddressException {
        try {
            ip.testAndStore(false);
            ip.setProgramCounter(ip.getProgramAddressRegister().getProgramCounter() + 1, false);
        } catch (TestAndSetInterrupt ex) {
            //  lock already clear - do nothing
        } finally {
            //  In any case, increment F0.x if/as appropriate
            ip.incrementIndexRegisterInF0();
        }
    }

    @Override
    public Instruction getInstruction() { return Instruction.TCS; }
}
