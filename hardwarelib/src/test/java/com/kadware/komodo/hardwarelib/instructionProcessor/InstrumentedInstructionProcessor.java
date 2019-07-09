/*
 * Copyright (c) 2018 by Kurt Duncan - All Rights Reserved
 */

package com.kadware.komodo.hardwarelib.instructionProcessor;

import com.kadware.komodo.hardwarelib.InstructionProcessor;
import com.kadware.komodo.hardwarelib.exceptions.UnresolvedAddressException;
import com.kadware.komodo.hardwarelib.interrupts.MachineInterrupt;

/**
 * Extended subclass of InstructionProcessor class, suitably instrumented for special testing
 */
public class InstrumentedInstructionProcessor extends InstructionProcessor {

    public InstrumentedInstructionProcessor(
        final String name,
        final int upi
    ) {
        super(name, upi);
    }

    @Override
    protected void executeInstruction(
    ) throws MachineInterrupt,
             UnresolvedAddressException {
        System.out.println(String.format("Executing Instruction at %012o --> %s",
                                         getProgramAddressRegister().getProgramCounter(),
                                         getCurrentInstruction().interpret(!getDesignatorRegister().getBasicModeEnabled(),
                                                                           getDesignatorRegister().getExecRegisterSetSelected())));
        super.executeInstruction();
    }
}
