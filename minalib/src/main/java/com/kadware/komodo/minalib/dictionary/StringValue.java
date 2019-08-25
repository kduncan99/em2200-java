/*
 * Copyright (c) 2018-2019 by Kurt Duncan - All Rights Reserved
 */

package com.kadware.komodo.minalib.dictionary;

import com.kadware.komodo.minalib.*;
import com.kadware.komodo.minalib.exceptions.InvalidParameterException;

/**
 * A Value which represents a string.
 * We do not do justification (all strings are left-justified) - it isn't worth the hassle...
 */
public class StringValue extends Value {

    public final CharacterMode _characterMode;
    public final String _value;

    /**
     * constructor
     * @param flagged (leading asterisk)
     * @param value actual string content
     * @param characterMode ASCII or Fieldata
     * @param precision single/double/default
     * @param justification left/right/default
     */
    private StringValue(
        final boolean flagged,
        final String value,
        final CharacterMode characterMode,
        final ValuePrecision precision,
        final ValueJustification justification
    ) {
        super(flagged, precision, justification);
        _characterMode = characterMode;
        _value = value;
    }

//    /**
//     * Compares an object to this object
//     * @param obj comparison object
//     * @return -1 if this object sorts before (is less than) the given object
//     *         +1 if this object sorts after (is greater than) the given object,
//     *          0 if both objects sort to the same position (are equal)
//     * @throws TypeException if there is no reasonable way to compare the objects
//     */
//    @Override
//    public int compareTo(
//        final Object obj
//    ) throws TypeException {
//        if (obj instanceof StringValue) {
//            StringValue sobj = (StringValue) obj;
//            if ( sobj._flagged == _flagged ) {
//                return _value.compareTo( ((StringValue) obj)._value );
//            }
//        }
//        throw new TypeException();
//    }

//    /**
//     * Create a new copy of this object, with the given flagged value
//     * @param newFlagged new value for Flagged attribute
//     * @return new Value
//     */
//    @Override
//    public Value copy(
//        final boolean newFlagged
//    ) {
//        return new StringValue(newFlagged, _value, _characterMode);
//    }

//    /**
//     * Creates a new copy of this object, with the given character mode
//     * @param newMode new value for Mode attribute
//     * @return new Value
//     */
//    public Value copy(
//        final CharacterMode newMode
//    ) {
//        return new StringValue(_flagged, _value, newMode);
//    }

    /**
     * Check for equality
     * @param obj comparison object
     * @return true if the objects are equal, else false
     */
    @Override
    public boolean equals(
        final Object obj
    ) {
        if (obj instanceof StringValue) {
            StringValue sobj = (StringValue) obj;
            return ( sobj._flagged == _flagged ) && sobj._value.equals(_value);
        } else {
            return false;
        }
    }

    @Override public ValueType getType() { return ValueType.String; }

    @Override public int hashCode() { return _value.hashCode(); }

//    /**
//     * Transform the value to an FloatingPointValue, if possible - probably won't mean anything though.
//     * @param locale locale of the instigating bit of text, for reporting diagnostics as necessary
//     * @param diagnostics where we post any necessary diagnostics
//     * @return new Value
//     */
//    @Override
//    public FloatingPointValue toFloatingPointValue(
//        final Locale locale,
//        Diagnostics diagnostics
//    ) {
//        long result;
//        if (_characterMode == CharacterMode.ASCII) {
//            result = Word36.stringToWord36ASCII(_value).getW();
//        } else {
//            result = Word36.stringToWord36Fieldata(_value).getW();
//        }
//
//        return new FloatingPointValue( _flagged, result );
//    }

//    /**
//     * Transform the value to an IntegerValue, if possible
//     * @param locale locale of the instigating bit of text, for reporting diagnostics as necessary
//     * @param diagnostics where we post any necessary diagnostics
//     * @return new Value
//     */
//    @Override
//    public IntegerValue toIntegerValue(
//        final Locale locale,
//        Diagnostics diagnostics
//    ) {
//        long result;
//        if (_characterMode == CharacterMode.ASCII) {
//            result = Word36.stringToWord36ASCII(_value).getW();
//        } else {
//            result = Word36.stringToWord36Fieldata(_value).getW();
//        }
//
//        return new IntegerValue(result);
//    }

//    /**
//     * Transform the value to a StringValue, if possible
//     * @param locale locale of the instigating bit of text, for reporting diagnostics as necessary
//     * @param characterMode desired character mode - we ignore this, as this applies only to conversions of something else
//     * @param diagnostics where we post any necessary diagnostics
//     * @return new Value
//     */
//    @Override
//    public StringValue toStringValue(
//        final Locale locale,
//        final CharacterMode characterMode,
//        Diagnostics diagnostics
//    ) {
//        return this;
//    }

    /**
     * For display purposes
     * @return displayable string
     */
    @Override
    public String toString() {
        return String.format("%s%s",
                             _flagged ? "*" : "",
                             _value);
    }


    //  ----------------------------------------------------------------------------------------------------------------------------
    //  Builder
    //  ----------------------------------------------------------------------------------------------------------------------------

    public static class Builder {

        CharacterMode _characterMode = CharacterMode.ASCII;
        boolean _flagged = false;
        ValueJustification _justification = ValueJustification.Default;
        ValuePrecision _precision = ValuePrecision.Default;
        String _value = null;

        public Builder setCharacterMode(CharacterMode value)        { _characterMode = value; return this; }
        public Builder setFlagged(boolean value)                    { _flagged = value; return this; }
        public Builder setJustification(ValueJustification value)   { _justification = value; return this; }
        public Builder setPrecision(ValuePrecision value)           { _precision = value; return this; }
        public Builder setValue(String value)                       { _value = value; return this; }

        public StringValue build(
        ) throws InvalidParameterException {
            if (_value == null) {
                throw new InvalidParameterException("Value not specified for IntegerValue builder");
            }

            return new StringValue(_flagged, _value, _characterMode, _precision, _justification);
        }
    }
}
