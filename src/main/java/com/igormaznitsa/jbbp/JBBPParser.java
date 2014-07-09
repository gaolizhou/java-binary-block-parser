/* 
 * Copyright 2014 Igor Maznitsa (http://www.igormaznitsa.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.igormaznitsa.jbbp;

import com.igormaznitsa.jbbp.compiler.*;
import com.igormaznitsa.jbbp.compiler.varlen.JBBPLengthEvaluator;
import com.igormaznitsa.jbbp.exceptions.JBBPParsingException;
import com.igormaznitsa.jbbp.io.*;
import com.igormaznitsa.jbbp.model.*;
import com.igormaznitsa.jbbp.utils.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * the Main class allows a user to parse a binary stream or block for predefined and precompiled script.
 */
public final class JBBPParser {
  /**
   * the Compiled block contains compiled script and extra information.
   */
  private final JBBPCompiledBlock compiledBlock;
  /**
   * The Bit order for stream operations.
   */
  private final JBBPBitOrder bitOrder;

  /**
   * The Constructor.
   * @param source the source script to parse binary blocks and streams, must not be null
   * @param bitOrder the bit order for bit reading operations, must not be null
   */
  private JBBPParser(final String source, final JBBPBitOrder bitOrder) {
    JBBPUtils.assertNotNull(source, "Script is null");
    JBBPUtils.assertNotNull(bitOrder, "Bit order is null");
    this.bitOrder = bitOrder;
    try {
      this.compiledBlock = JBBPCompiler.compile(source);
    }
    catch (IOException ex) {
      throw new RuntimeException("Can't compile script for unexpected IO Exception", ex);
    }
  }

  /**
   * Inside method to parse a structure.
   * @param inStream the input stream, must not be null
   * @param positionAtCompiledBlock the current position in the compiled script block
   * @param namedNumericFieldMap the named numeric field map
   * @param positionAtNamedFieldList the current position at the named field list
   * @param positionAtVarLengthProcessors the current position at the variable array length processor list
   * @param nonskip the flag shows that the read fields must be processed if it is true, or their content must be ignored if it is false
   * @return list of read fields for the structure
   * @throws IOException it will be thrown for transport errors
   */
  private List<JBBPAbstractField> parseStruct(final JBBPBitInputStream inStream, final AtomicInteger positionAtCompiledBlock, final JBBPNamedNumericFieldMap namedNumericFieldMap, final AtomicInteger positionAtNamedFieldList, final AtomicInteger positionAtVarLengthProcessors, final boolean nonskip) throws IOException {
    final List<JBBPAbstractField> fields = nonskip ? new ArrayList<JBBPAbstractField>() : null;
    final byte[] compiled = this.compiledBlock.getCompiledData();

    boolean structEndNotMeet = true;

    while (structEndNotMeet && positionAtCompiledBlock.get() < compiled.length) {
      final int instructionStartOffset = positionAtCompiledBlock.getAndIncrement();
      final int code = compiled[instructionStartOffset] & 0xFF;

      final JBBPNamedFieldInfo name;
      if ((code & JBBPCompiler.FLAG_NAMED) != 0) {
        name = compiledBlock.getNamedFields()[positionAtNamedFieldList.getAndIncrement()];
        if (name == null) {
          throw new Error("Internal exception, contact developer!");
        }
      }
      else {
        name = null;
      }

      final int arrayLength;
      final boolean nonsizedArray;
      
      boolean checkArrayLength = false;
      switch (code & (JBBPCompiler.FLAG_ARRAY | JBBPCompiler.FLAG_EXPRESSIONORWHOLE)) {
        case JBBPCompiler.FLAG_ARRAY: {
          arrayLength = JBBPUtils.unpackInt(compiled, positionAtCompiledBlock);
          nonsizedArray = false;
          checkArrayLength = true;
        }
        break;
        case JBBPCompiler.FLAG_EXPRESSIONORWHOLE: {
          nonsizedArray = true;
          arrayLength = 0;
        }
        break;
        case JBBPCompiler.FLAG_ARRAY | JBBPCompiler.FLAG_EXPRESSIONORWHOLE: {
          final JBBPLengthEvaluator evaluator = this.compiledBlock.getArraySizeEvaluators()[positionAtVarLengthProcessors.getAndIncrement()];
          arrayLength = evaluator.eval(inStream, instructionStartOffset, this.compiledBlock, namedNumericFieldMap);
          nonsizedArray = false;
          checkArrayLength = true;
        }
        break;
        default: {
          nonsizedArray = false;
          arrayLength = -1;
        }
        break;
      }

      if (checkArrayLength){
        if (arrayLength < 0) {
          throw new JBBPParsingException("Detected negative calculated array length for field '" + (name == null ? "<NONAMED>" : name.getFieldPath()) + "\' [" + JBBPUtils.int2msg(arrayLength)+']');
        }
      }
      
      JBBPAbstractField singleValueField = null;

      switch (code & 0xF) {
        case JBBPCompiler.CODE_ALIGN: {
          if (nonskip) {
            final int alignByteNumber = JBBPUtils.unpackInt(compiled, positionAtCompiledBlock);

            inStream.alignByte();
            
            if (alignByteNumber > 0) {
              while(inStream.getCounter() % alignByteNumber != 0){
                final int skeptByte = inStream.read();
                if (skeptByte<0) throw new EOFException("Can't align for "+alignByteNumber+" for EOFException");
              }
            }
          }
        }
        break;
        case JBBPCompiler.CODE_SKIP: {
          if (nonskip) {
            final int skipByteNumber = JBBPUtils.unpackInt(compiled, positionAtCompiledBlock);
            if (skipByteNumber > 0) {
              
              final long skeptBytes = inStream.skip(skipByteNumber);

              if (skeptBytes != skipByteNumber){
                throw new EOFException("Can't skip "+skipByteNumber+" byte(s), skept only "+skeptBytes);
              }
            }
          }
        }
        break;
        case JBBPCompiler.CODE_BIT: {
          if (nonskip) {
            final JBBPBitNumber bitNumber = JBBPBitNumber.decode(JBBPUtils.unpackInt(compiled, positionAtCompiledBlock));
            if (arrayLength < 0) {
              singleValueField = new JBBPFieldBit(name, inStream.readBits(bitNumber));
            }
            else {
              if (nonsizedArray) {
                fields.add(new JBBPFieldArrayBit(name, inStream.readBitsArray(-1, bitNumber)));
              }
              else {
                if (arrayLength > 0) {
                  fields.add(new JBBPFieldArrayBit(name, inStream.readBitsArray(arrayLength, bitNumber)));
                }
              }
            }
          }
        }
        break;
        case JBBPCompiler.CODE_BOOL: {
          if (nonskip) {
            if (arrayLength < 0) {
              final int value = inStream.read();
              if (value < 0) {
                throw new EOFException("Can't read boolean value for field '" + name + '\'');
              }
              singleValueField = new JBBPFieldBoolean(name, value != 0);
            }
            else {
              final byte[] bytearray;
              if (nonsizedArray) {
                bytearray = inStream.readByteArray(-1);
              }
              else if (arrayLength > 0) {
                bytearray = inStream.readByteArray(arrayLength);
              }
              else {
                bytearray = null;
              }
              if (bytearray != null) {
                final boolean[] result = new boolean[bytearray.length];
                for (int i = 0; i < bytearray.length; i++) {
                  result[i] = bytearray[i] != 0;
                }
                fields.add(new JBBPFieldArrayBoolean(name, result));
              }
            }
          }
        }
        break;
        case JBBPCompiler.CODE_BYTE: {
          if (nonskip) {
            if (arrayLength < 0) {
              final int value = inStream.read();
              if (value < 0) {
                throw new EOFException("Can't read byte value for field '" + name + '\'');
              }
              singleValueField = new JBBPFieldByte(name, (byte) value);
            }
            else {
              if (nonsizedArray) {
                fields.add(new JBBPFieldArrayByte(name, inStream.readByteArray(-1)));
              }
              else if (arrayLength > 0) {
                fields.add(new JBBPFieldArrayByte(name, inStream.readByteArray(arrayLength)));
              }
            }
          }
        }
        break;
        case JBBPCompiler.CODE_UBYTE: {
          if (nonskip) {
            if (arrayLength < 0) {
              final int value = inStream.read();
              if (value < 0) {
                throw new EOFException("Can't read unsigned byte value for field '" + name + '\'');
              }
              singleValueField = new JBBPFieldUByte(name, (byte) value);
            }
            else {
              if (nonsizedArray) {
                fields.add(new JBBPFieldArrayUByte(name, inStream.readByteArray(-1)));
              }
              else if (arrayLength > 0) {
                fields.add(new JBBPFieldArrayUByte(name, inStream.readByteArray(arrayLength)));
              }
            }
          }
        }
        break;
        case JBBPCompiler.CODE_INT: {
          if (nonskip) {
            if (arrayLength < 0) {
              final int value = inStream.readInt((code & JBBPCompiler.FLAG_LITTLE_ENDIAN) == 0 ? JBBPByteOrder.BIG_ENDIAN : JBBPByteOrder.LITTLE_ENDIAN);
              singleValueField = new JBBPFieldInt(name, value);
            }
            else {
              if (nonsizedArray) {
                fields.add(new JBBPFieldArrayInt(name, inStream.readIntArray(-1, (code & JBBPCompiler.FLAG_LITTLE_ENDIAN) == 0 ? JBBPByteOrder.BIG_ENDIAN : JBBPByteOrder.LITTLE_ENDIAN)));
              }
              else if (arrayLength > 0) {
                fields.add(new JBBPFieldArrayInt(name, inStream.readIntArray(arrayLength, (code & JBBPCompiler.FLAG_LITTLE_ENDIAN) == 0 ? JBBPByteOrder.BIG_ENDIAN : JBBPByteOrder.LITTLE_ENDIAN)));
              }
            }
          }
        }
        break;
        case JBBPCompiler.CODE_LONG: {
          if (nonskip) {
            if (arrayLength < 0) {
              final long value = inStream.readLong((code & JBBPCompiler.FLAG_LITTLE_ENDIAN) == 0 ? JBBPByteOrder.BIG_ENDIAN : JBBPByteOrder.LITTLE_ENDIAN);
              singleValueField = new JBBPFieldLong(name, value);
            }
            else {
              if (nonsizedArray) {
                fields.add(new JBBPFieldArrayLong(name, inStream.readLongArray(-1, (code & JBBPCompiler.FLAG_LITTLE_ENDIAN) == 0 ? JBBPByteOrder.BIG_ENDIAN : JBBPByteOrder.LITTLE_ENDIAN)));
              }
              else if (arrayLength > 0) {
                fields.add(new JBBPFieldArrayLong(name, inStream.readLongArray(arrayLength, (code & JBBPCompiler.FLAG_LITTLE_ENDIAN) == 0 ? JBBPByteOrder.BIG_ENDIAN : JBBPByteOrder.LITTLE_ENDIAN)));
              }
            }
          }
        }
        break;
        case JBBPCompiler.CODE_SHORT: {
          if (nonskip) {
            if (arrayLength < 0) {
              final int value = inStream.readUnsignedShort((code & JBBPCompiler.FLAG_LITTLE_ENDIAN) == 0 ? JBBPByteOrder.BIG_ENDIAN : JBBPByteOrder.LITTLE_ENDIAN);
              singleValueField = new JBBPFieldShort(name, (short) value);
            }
            else {
              if (nonsizedArray) {
                fields.add(new JBBPFieldArrayShort(name, inStream.readShortArray(-1, (code & JBBPCompiler.FLAG_LITTLE_ENDIAN) == 0 ? JBBPByteOrder.BIG_ENDIAN : JBBPByteOrder.LITTLE_ENDIAN)));
              }
              else if (arrayLength > 0) {
                fields.add(new JBBPFieldArrayShort(name, inStream.readShortArray(arrayLength, (code & JBBPCompiler.FLAG_LITTLE_ENDIAN) == 0 ? JBBPByteOrder.BIG_ENDIAN : JBBPByteOrder.LITTLE_ENDIAN)));
              }
            }
          }
        }
        break;
        case JBBPCompiler.CODE_USHORT: {
          if (nonskip) {
            if (arrayLength < 0) {
              final int value = inStream.readUnsignedShort((code & JBBPCompiler.FLAG_LITTLE_ENDIAN) == 0 ? JBBPByteOrder.BIG_ENDIAN : JBBPByteOrder.LITTLE_ENDIAN);
              singleValueField = new JBBPFieldUShort(name, (short) value);
            }
            else {
              if (nonsizedArray) {
                fields.add(new JBBPFieldArrayUShort(name, inStream.readShortArray(-1, (code & JBBPCompiler.FLAG_LITTLE_ENDIAN) == 0 ? JBBPByteOrder.BIG_ENDIAN : JBBPByteOrder.LITTLE_ENDIAN)));
              }
              else if (arrayLength > 0) {
                fields.add(new JBBPFieldArrayUShort(name, inStream.readShortArray(arrayLength, (code & JBBPCompiler.FLAG_LITTLE_ENDIAN) == 0 ? JBBPByteOrder.BIG_ENDIAN : JBBPByteOrder.LITTLE_ENDIAN)));
              }
            }
          }
        }
        break;
        case JBBPCompiler.CODE_STRUCT_START: {
          if (arrayLength < 0) {
            final List<JBBPAbstractField> structFields = parseStruct(inStream, positionAtCompiledBlock, namedNumericFieldMap, positionAtNamedFieldList, positionAtVarLengthProcessors, nonskip);
            // offset
            JBBPUtils.unpackInt(compiled, positionAtCompiledBlock);
            if (nonskip) {
              fields.add(new JBBPFieldStruct(name, structFields.toArray(new JBBPAbstractField[structFields.size()])));
            }
          }
          else {
            final int nameFieldCurrent = positionAtNamedFieldList.get();
            final int varLenProcCurrent = positionAtVarLengthProcessors.get();

            final JBBPFieldStruct[] result;
            if (nonskip) {
              if (nonsizedArray) {
                // read till the stream end
                final List<JBBPFieldStruct> list = new ArrayList<JBBPFieldStruct>();
                while (inStream.hasAvailableData()) {
                  positionAtNamedFieldList.set(nameFieldCurrent);
                  positionAtVarLengthProcessors.set(varLenProcCurrent);

                  final List<JBBPAbstractField> fieldsForStruct = parseStruct(inStream, positionAtCompiledBlock, namedNumericFieldMap, positionAtNamedFieldList, positionAtVarLengthProcessors, nonskip);
                  list.add(new JBBPFieldStruct(name, fieldsForStruct));

                  final int structStart = JBBPUtils.unpackInt(compiled, positionAtCompiledBlock);

                  if (inStream.hasAvailableData()) {
                    positionAtCompiledBlock.set(structStart + 1);
                  }
                }

                if (list.isEmpty()){
                  // list is empty then we need to skip start struct offset manually
                  JBBPUtils.unpackInt(compiled, positionAtCompiledBlock);
                }
                
                result = list.toArray(new JBBPFieldStruct[list.size()]);
              }
              else {
                // read number of items
                if (arrayLength == 0) {
                  // skip the structure
                  result = null;
                  parseStruct(inStream, positionAtCompiledBlock, namedNumericFieldMap, positionAtNamedFieldList, positionAtVarLengthProcessors, false);
                  JBBPUtils.unpackInt(compiled, positionAtCompiledBlock);
                }
                else {
                  result = new JBBPFieldStruct[arrayLength];
                  for (int i = 0; i < arrayLength; i++) {
                    positionAtNamedFieldList.set(nameFieldCurrent);

                    final List<JBBPAbstractField> fieldsForStruct = parseStruct(inStream, positionAtCompiledBlock, namedNumericFieldMap, positionAtNamedFieldList, positionAtVarLengthProcessors, nonskip);
                    final int structStart = JBBPUtils.unpackInt(compiled, positionAtCompiledBlock);

                    result[i] = new JBBPFieldStruct(name, fieldsForStruct);

                    if (i < arrayLength - 1) {
                      // not the last
                      positionAtCompiledBlock.set(structStart + 1);
                    }
                  }
                }
              }

              if (result!=null) fields.add(new JBBPFieldArrayStruct(name, result));
            }
            else {
              parseStruct(inStream, positionAtCompiledBlock, namedNumericFieldMap, positionAtNamedFieldList, positionAtVarLengthProcessors, nonskip);
              JBBPUtils.unpackInt(compiled, positionAtCompiledBlock);
            }
          }
        }
        break;
        case JBBPCompiler.CODE_STRUCT_END: {
          // we just left the method and the caller must process the structure offset start address for the structure
          structEndNotMeet = false;
        }
        break;
        default:
          throw new Error("Detected unexpected field type! Contact developer! [" + code + ']');
      }

      if (singleValueField != null) {
        fields.add(singleValueField);
        if (namedNumericFieldMap != null && singleValueField instanceof JBBPNumericField && singleValueField.getNameInfo() != null) {
          namedNumericFieldMap.putField((JBBPNumericField)singleValueField);
        }
      }

    }

    return fields;
  }

  /**
   * Parse an input stream.
   * @param in an input stream which content should be parsed, it must not be null
   * @return the parsed content as the root structure
   * @throws IOException it will be thrown for transport errors
   */
  public JBBPFieldStruct parse(final InputStream in) throws IOException {
    return this.parse(in, null);
  }
  
  /**
   * Parse am input stream with defined external value provider.
   * @param in an input stream which content will be parsed, it must not be null
   * @param externalValueProvider an external value provider, it can be null but only if the script doesn't have fields desired the provider
   * @return the parsed content as the root structure
   * @throws IOException it will be thrown for transport errors
   */
  public JBBPFieldStruct parse(final InputStream in, final JBBPExternalValueProvider externalValueProvider) throws IOException {
    final JBBPBitInputStream bitInStream = in instanceof JBBPBitInputStream ? (JBBPBitInputStream) in : new JBBPBitInputStream(in, bitOrder);

    final JBBPNamedNumericFieldMap fieldMap;
    if (this.compiledBlock.hasVarArrays()) {
      fieldMap = new JBBPNamedNumericFieldMap(externalValueProvider);
    }
    else {
      fieldMap = null;
    }

    return new JBBPFieldStruct(new JBBPNamedFieldInfo("", "", -1), parseStruct(bitInStream, new AtomicInteger(), fieldMap, new AtomicInteger(), new AtomicInteger(), true));
  }

  /**
   * Parse a byte array content.
   * @param array a byte array which content should be parsed, it must not be null
   * @return the parsed content as the root structure
   * @throws IOException it will be thrown for transport errors
   */
  public JBBPFieldStruct parse(final byte[] array) throws IOException {
    JBBPUtils.assertNotNull(array, "Array must not be null");
    return this.parse(new ByteArrayInputStream(array),null);
  }

  /**
   * Parse a byte array content with defined external value provider.
   * @param array a byte array which content should be parsed, it must not be
   * null
   * @param externalValueProvider an external value provider, it can be null but
   * only if the script doesn't have fields desired the provider
   * @return the parsed content as the root structure
   * @throws IOException it will be thrown for transport errors
   */
  public JBBPFieldStruct parse(final byte[] array, final JBBPExternalValueProvider externalValueProvider) throws IOException {
    JBBPUtils.assertNotNull(array, "Array must not be null");
    return this.parse(new ByteArrayInputStream(array), externalValueProvider);
  }

  /**
   * Prepare a parser for a script and a bit order.
   * @param script a text script contains field order and types reference, it must not be null 
   * @param bitOrder the bit order for reading operations, it must not be null
   * @return the prepared parser for the script
   * @see JBBPBitOrder#LSB0
   * @see JBBPBitOrder#MSB0
   */
  public static JBBPParser prepare(final String script, final JBBPBitOrder bitOrder) {
    return new JBBPParser(script, bitOrder);
  }

  /**
   * Prepare a parser for a script with usage of the default bit order LSB0.
   * @param script a text script contains field order and types reference, it
   * must not be null
   * @return the prepared parser for the script
   * @see JBBPBitOrder#LSB0
   */
  public static JBBPParser prepare(final String script) {
    return JBBPParser.prepare(script, JBBPBitOrder.LSB0);
  }
}