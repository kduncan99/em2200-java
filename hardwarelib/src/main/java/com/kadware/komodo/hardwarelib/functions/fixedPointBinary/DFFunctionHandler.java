/*
 * Copyright (c) 2018-2019 by Kurt Duncan - All Rights Reserved
 */

package com.kadware.komodo.hardwarelib.functions.fixedPointBinary;

import com.kadware.komodo.baselib.InstructionWord;
import com.kadware.komodo.baselib.OnesComplement;
import com.kadware.komodo.hardwarelib.InstructionProcessor;
import com.kadware.komodo.hardwarelib.exceptions.UnresolvedAddressException;
import com.kadware.komodo.hardwarelib.interrupts.ArithmeticExceptionInterrupt;
import com.kadware.komodo.hardwarelib.interrupts.MachineInterrupt;
import com.kadware.komodo.hardwarelib.functions.InstructionHandler;

/**
 * Handles the DF instruction f=036
 */
@SuppressWarnings("Duplicates")
public class DFFunctionHandler extends InstructionHandler {

    private final long[] _dividend = { 0, 0 };
    private final long[] _divisor = { 0, 0 };
    private final OnesComplement.DivideResult _dr = new OnesComplement.DivideResult();

    @Override
    public synchronized void handle(
        final InstructionProcessor ip,
        final InstructionWord iw
    ) throws MachineInterrupt,
             UnresolvedAddressException {
        _dividend[0] = ip.getExecOrUserARegister((int)iw.getA()).getW();
        _dividend[1] = ip.getExecOrUserARegister((int)iw.getA() + 1).getW();
        OnesComplement.rightShiftAlgebraic72(_dividend, 1, _dividend);

        _divisor[1] = ip.getOperand(true, true, true, true);
        _divisor[0] = OnesComplement.isNegative36(_divisor[1]) ? OnesComplement.NEGATIVE_ZERO_36 : OnesComplement.POSITIVE_ZERO_36;

        long quotient = 0;
        long remainder = 0;
        try {
            OnesComplement.divide72(_dividend, _divisor, _dr);
            if (!OnesComplement.isZero36(_dr._quotient[0])) {
                //  quotient is too large - divide check, and maybe an interrupt
                ip.getDesignatorRegister().setDivideCheck(true);
                if (ip.getDesignatorRegister().getArithmeticExceptionEnabled()) {
                    throw new ArithmeticExceptionInterrupt(ArithmeticExceptionInterrupt.Reason.DivideCheck);
                }
            } else {
                quotient = _dr._quotient[1];
                remainder = _dr._remainder[1];
            }
        } catch (DivideByZeroException ex) {
            //  divisor is zero - divide check, and maybe an interrupt
            ip.getDesignatorRegister().setDivideCheck(true);
            if (ip.getDesignatorRegister().getArithmeticExceptionEnabled()) {
                throw new ArithmeticExceptionInterrupt(ArithmeticExceptionInterrupt.Reason.DivideCheck);
            }
        }

        ip.getExecOrUserARegister((int)iw.getA()).setW(quotient);
        ip.getExecOrUserARegister((int)iw.getA() + 1).setW(remainder);
    }

    @Override
    public Instruction getInstruction() { return Instruction.DF; }
}
