/*
 * Copyright (c) 2018-2020 by Kurt Duncan - All Rights Reserved
 */

package com.kadware.komodo.kex.kasm.dictionary;

import com.kadware.komodo.baselib.FieldDescriptor;
import com.kadware.komodo.kex.kasm.Form;
import com.kadware.komodo.kex.kasm.LineSpecifier;
import com.kadware.komodo.kex.kasm.Locale;
import com.kadware.komodo.kex.kasm.UndefinedReference;
import com.kadware.komodo.kex.kasm.UndefinedReferenceToLocationCounter;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class Test_IntegerValue {

    @Test
    public void integrate_good() {
        FieldDescriptor fdur1 = new FieldDescriptor(18, 18);
        UndefinedReference[] initialRefs = {
            new UndefinedReferenceToLocationCounter(fdur1, false, 25)
        };
        IntegerValue initial = new IntegerValue.Builder().setValue(0_111111_222222L)
                                                         .setReferences(initialRefs)
                                                         .setForm(Form.EI$Form)
                                                         .setFlagged(true)
                                                         .build();

        FieldDescriptor fdur2 = new FieldDescriptor(30, 6);
        UndefinedReference[] refs1 = {
            new UndefinedReferenceToLocationCounter(fdur2, false, 13)
        };
        FieldDescriptor compFD1 = new FieldDescriptor(12, 6);
        IntegerValue compValue1 = new IntegerValue.Builder().setValue(025).setReferences(refs1).build();
        FieldDescriptor compFD2 = new FieldDescriptor(21, 12);
        IntegerValue compValue2 = new IntegerValue.Builder().setValue(0_1111).build();
        FieldDescriptor compFD3 = new FieldDescriptor(1, 1);
        IntegerValue compValue3 = new IntegerValue.Builder().setValue(1).build();

        FieldDescriptor[] compFDs = { compFD1, compFD2, compFD3 };
        IntegerValue[] compValues = { compValue1, compValue2, compValue3 };

        Locale intLocale = new Locale(new LineSpecifier(2, 25), 30);
        IntegerValue.IntegrateResult result = IntegerValue.integrate(initial, compFDs ,compValues, intLocale);

        assertTrue(result._diagnostics.isEmpty());
        assertEquals(intLocale, result._value._locale);
        assertEquals(Form.EI$Form, result._value._form);
        assertFalse(result._value._flagged);
        assertEquals(ValuePrecision.Default, result._value._precision);
        assertEquals(0_311136_233332L, result._value._value.get().longValue());
        UndefinedReference[] urs = result._value._references;
        assertEquals(2, urs.length);
        assertTrue(urs[0] instanceof UndefinedReferenceToLocationCounter);
        assertTrue(urs[1] instanceof UndefinedReferenceToLocationCounter);
        UndefinedReferenceToLocationCounter urlc0 = (UndefinedReferenceToLocationCounter) urs[0];
        UndefinedReferenceToLocationCounter urlc1 = (UndefinedReferenceToLocationCounter) urs[1];
        assertEquals(25, urlc0._locationCounterIndex);
        assertEquals(fdur1, urlc0._fieldDescriptor);
        assertFalse(urlc0._isNegative);
        assertEquals(13, urlc1._locationCounterIndex);
        assertEquals(compFD1, urlc1._fieldDescriptor);
        assertFalse(urlc1._isNegative);
    }
}
