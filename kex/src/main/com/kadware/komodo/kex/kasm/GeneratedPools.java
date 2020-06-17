/*
 * Copyright (c) 2018-2020 by Kurt Duncan - All Rights Reserved
 */

package com.kadware.komodo.kex.kasm;

import com.kadware.komodo.baselib.DoubleWord36;
import com.kadware.komodo.baselib.exceptions.NotFoundException;
import com.kadware.komodo.kex.kasm.diagnostics.FatalDiagnostic;
import com.kadware.komodo.kex.kasm.diagnostics.ValueDiagnostic;
import com.kadware.komodo.kex.kasm.dictionary.Dictionary;
import com.kadware.komodo.kex.kasm.dictionary.IntegerValue;
import com.kadware.komodo.kex.kasm.dictionary.Value;
import com.kadware.komodo.kex.kasm.dictionary.ValueType;
import java.math.BigInteger;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Container for the various GeneratedPool objects which are created during an assembly
 */
public class GeneratedPools extends TreeMap<Integer, GeneratedPool> {

    /**
     * Advances the next offset for the generated word map for the indicated location counter pool.
     * If the pool hasn't been created, it is created now.
     * Used for $RES directive
     */
    public void advance(
        final int lcIndex,
        final int count
    ) {
        GeneratedPool gp = obtainPool(lcIndex);
        gp.advance(count);
    }

    /**
     * Generates a word (with possibly a form attached) for a given location counter index and offset,
     * and places it into the appropriate location counter pool within the given context.
     * Also associates it with the given text line.
     * @param textLine the top-level TextLine object which is responsible for generating this code
     * @param locale location of the text entity generating this word
     * @param lcIndex index of the location counter pool where-in the value is to be placed
     * @param value the integer/intrinsic value to be used
     */
    void generate(
        final TextLine textLine,
        final Locale locale,
        final int lcIndex,
        final IntegerValue value
    ) {
        GeneratedPool gp = obtainPool(lcIndex);
        gp.generate(textLine, locale, value);
    }

    /**
     * Generates the multiple words for a given location counter index
     * and places them into the appropriate location counter pool.
     * Also associates it with the given text line.
     * @param textLine the top-level TextLine object which is responsible for generating this code
     * @param locale location of the text entity generating this word
     * @param lcIndex index of the location counter pool where-in the value is to be placed
     * @param values the values to be used
     */
    void generate(
        final TextLine textLine,
        final Locale locale,
        final int lcIndex,
        final long[] values
    ) {
        GeneratedPool gp = obtainPool(lcIndex);
        gp.generate(textLine, locale, values);
    }

    /**
     * Generates a word with a form attached at the next generated-word offset.
     * Also associates it with the given text line.
     * The form describes 1 or more fields, the totality of which are expected to describe 36 bits.
     * The values parameter is an array with as many entities as there are fields in the form.
     * Each value must fit within the size of that value's respective field.
     * The overall integer portion of the generated value is the respective component integer values
     * shifted into their field positions, and or'd together.
     * The individual values should not have forms attached, but they may have undefined references.
     * All such undefined references are adjusted to match the field description of the particular
     * field to which the reference applies.
     * @param textLine the top-level TextLine object which is responsible for generating this code
     * @param locale location of the text entity generating this word
     * @param lcIndex index of the location counter pool where-in the value is to be placed
     * @param form form describing the fields for which values are specified in the values parameter
     * @param values array of component values, each with potential undefined refereces but no attached forms
     * @param assembler where we post diagnostics if any need to be generated
     * @return value indicating the location which applies to the word which was just generated...
     *          This value is used in generating literals, and is not used in other situations.
     */
    public IntegerValue generate(
        final TextLine textLine,
        final Locale locale,
        final int lcIndex,
        final Form form,
        final IntegerValue[] values,
        final Assembler assembler
    ) {
        GeneratedPool gp = obtainPool(lcIndex);
        return gp.generate(textLine, locale, form, values, assembler);
    }

    /**
     * Obtains a reference to the GeneratedPool corresponding to the given location counter index.
     * If such a pool does not exist, it is created.
     * @param lcIndex index of the desired pool
     * @return reference to the pool
     */
    public GeneratedPool obtainPool(
        final int lcIndex
    ) {
        GeneratedPool gp = get(lcIndex);
        if (gp == null) {
            gp = new GeneratedPool(lcIndex);
            put(lcIndex, gp);
        }
        return gp;
    }

    /**
     * Resolves any lingering undefined references once initial assembly is complete, for one particular IntegerValue object.
     * These will be the forward-references we picked up along the way.
     * No point checking for loc ctr refs, those aren't resolved until link time.
     * @param originalValue an IntegerValue presumably containing undefined references which we can resolve
     * @param assembler the current controlling assembler (at this stage, it should be the main assembler)
     * @return a newly-generated IntegerValue which has all resolvable references satisfied and integrated into the base value.
     */
    private IntegerValue resolveReferences(
        final IntegerValue originalValue,
        final Assembler assembler
    ) {
        IntegerValue newValue = originalValue;
        if (originalValue._references.length > 0) {
            BigInteger newDiscreteValue = originalValue._value.get();
            List<UnresolvedReference> newURefs = new LinkedList<>();
            for (UnresolvedReference uRef : originalValue._references) {
                if (uRef instanceof UnresolvedReferenceToLabel) {
                    UnresolvedReferenceToLabel lRef = (UnresolvedReferenceToLabel) uRef;
                    try {
                        Dictionary.ValueInfo vInfo = assembler.getDictionary().getValueInfo(lRef._label);
                        Value lookupValue = vInfo._value;
                        if (lookupValue.getType() != ValueType.Integer) {
                            String msg = String.format("Reference '%s' does not resolve to an integer",
                                                       lRef._label);
                            assembler.appendDiagnostic(new ValueDiagnostic(originalValue._locale, msg));
                        } else {
                            IntegerValue lookupIntegerValue = (IntegerValue) lookupValue;
                            BigInteger addend = lookupIntegerValue._value.get();
                            if (lRef._isNegative) {
                                addend = addend.negate();
                            }
                            newDiscreteValue = newDiscreteValue.add(addend);
                            for (UnresolvedReference urSub : lookupIntegerValue._references) {
                                newURefs.add(urSub.copy(lRef._fieldDescriptor));
                            }
                        }
                    } catch (NotFoundException ex) {
                        //  reference is still not found - propagate it
                        newURefs.add(uRef);
                    }
                } else if (uRef instanceof UnresolvedReferenceToLiteral) {
                    //  Any errors in resolving the reference are an internal error.
                    //  i.e., Should Never Happen.  Thus, producing a fatal exception with some debug info.
                    UnresolvedReferenceToLiteral litRef = (UnresolvedReferenceToLiteral) uRef;
                    GeneratedPool referredPool = get(litRef._locationCounterIndex);
                    if (referredPool == null) {
                        String msg = "Internal error resolving a reference to a literal";
                        assembler.appendDiagnostic(new FatalDiagnostic(null, msg));
                    } else {
                        int offset = referredPool.getNextOffset() + litRef._literalOffset;
                        newDiscreteValue = newDiscreteValue.add(BigInteger.valueOf(offset));
                    }
                } else {
                    newURefs.add(uRef);
                }
            }

            newValue = new IntegerValue.Builder().setLocale(originalValue._locale)
                                                 .setValue(new DoubleWord36(newDiscreteValue))
                                                 .setForm(originalValue._form)
                                                 .setReferences(newURefs)
                                                 .build();
        }

        return newValue;
    }

    /**
     * Resolves all resolvable undefined references in this GeneratedPool object
     * @param assembler the controlling assembler (should be the main assembler)
     */
    public void resolveReferences(
        final Assembler assembler
    ) {
        for (GeneratedPool pool : values()) {
            Iterator<Map.Entry<Integer, GeneratedWord>> gwIter = pool.getGeneratedWordsIterator();
            while (gwIter.hasNext()) {
                Map.Entry<Integer, GeneratedWord> entry = gwIter.next();
                Integer lcOffset = entry.getKey();
                GeneratedWord gwOriginal = entry.getValue();
                IntegerValue originalValue = gwOriginal._value;
                IntegerValue newValue = resolveReferences(originalValue, assembler);
                if (!newValue.equals(originalValue)) {
                    pool.storeGeneratedWord(lcOffset, gwOriginal.copy(newValue));
                }
            }
        }
    }
}
