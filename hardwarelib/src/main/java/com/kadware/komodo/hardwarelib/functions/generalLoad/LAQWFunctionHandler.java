/*
 * Copyright (c) 2018-2019 by Kurt Duncan - All Rights Reserved
 */

package com.kadware.komodo.hardwarelib.functions.generalLoad;

import com.kadware.komodo.baselib.InstructionWord;
import com.kadware.komodo.hardwarelib.InstructionProcessor;
import com.kadware.komodo.hardwarelib.exceptions.UnresolvedAddressException;
import com.kadware.komodo.hardwarelib.interrupts.MachineInterrupt;
import com.kadware.komodo.hardwarelib.functions.InstructionHandler;

/**
 * Handles the LAQW instruction f=07 j=04
 * Operates much like LA,[q1-q4], except that the quarter-word designation is taken from bits 4-5 of X(x).
 * X-incrementation is not supported - results are undefined.
 */
public class LAQWFunctionHandler extends InstructionHandler {

    //  Array of j-field values indexed by the quarter-word designator where
    //  des==0 -> Q1, des==1 -> Q2, etc.
    private static final int J_FIELDS[] = { 7, 4, 6, 5 };

    @Override
    public void handle(
        final InstructionProcessor ip,
        final InstructionWord iw
    ) throws MachineInterrupt,
             UnresolvedAddressException {
        int designator = (int)ip.getExecOrUserXRegister((int)iw.getX()).getS1() & 03;
        int jField = J_FIELDS[designator];
        long operand = ip.getPartialOperand(jField, true);
        ip.setGeneralRegister(ip.getExecOrUserARegisterIndex((int)iw.getA()), operand);
    }

    @Override
    public Instruction getInstruction() { return Instruction.LAQW; }
}
