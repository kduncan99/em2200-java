/*
 * Copyright (c) 2019 by Kurt Duncan - All Rights Reserved
 */

package com.kadware.em2200.hardwarelib.functions.procedureControl;

import com.kadware.em2200.baselib.InstructionWord;
import com.kadware.em2200.hardwarelib.InstructionProcessor;
import com.kadware.em2200.hardwarelib.exceptions.UnresolvedAddressException;
import com.kadware.em2200.hardwarelib.functions.InstructionHandler;
import com.kadware.em2200.hardwarelib.interrupts.MachineInterrupt;

/**
 * Handles the LDJ instruction f=07 j=012
 */
public class LDJFunctionHandler extends InstructionHandler {

    @Override
    public void handle(
        final InstructionProcessor ip,
        final InstructionWord iw
    ) throws MachineInterrupt,
             UnresolvedAddressException {
        /*
The LDJ instruction is a special case of the LBJ instruction (see 6.16.1), differing only in the
selection of a Basic_Mode Base_Register. For LDJ, bits 1–2 of Xa (BDR field) are ignored and the
Base_Register selected is B14 if DB31 = 0 (Basic_Mode Base_Register Selection) or B15 if
DB31 = 1.
See 4.6 for further clarification of addressing instructions, including a generic Base_Register
Manipulation algorithm (see 4.6.4) and a list of the restrictions placed on executive software on
the manipulation of addressing structures, allowing for model_dependent addressing instruction
acceleration schemes (see 4.6.5).
All information and algorithms pertaining to the LBJ instruction apply to the LDJ with the addition
that DB31 determines the Base_Register selected as described the preceding paragraph.
         */
    }

    @Override
    public Instruction getInstruction() { return Instruction.LDJ; }
}
