/*
 * Copyright (c) 2018-2020 by Kurt Duncan - All Rights Reserved
 */

package com.kadware.komodo.kex.kasm.expressions.items;

import com.kadware.komodo.kex.kasm.*;
import com.kadware.komodo.kex.kasm.dictionary.Value;

/**
 * expression item containing a Value object
 */
public class ValueItem extends OperandItem {

    public final Value _value;

    /**
     * Constructor
     * @param value Value which we represent
     */
    public ValueItem(
        final Value value
    ) {
        super(value._locale);
        _value = value;
    }

    /**
     * Resolves the value of this item - basically, we just return the Value object
     *
     * @param assembler@return a Value representing this operand
     */
    @Override
    public Value resolve(
        final Assembler assembler
    ) {
        return _value;
    }

    @Override
    public String toString() {
        return "ValueItem:" + _value.toString();
    }
}
