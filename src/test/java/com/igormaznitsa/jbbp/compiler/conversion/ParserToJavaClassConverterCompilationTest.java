/*
 * Copyright 2017 Igor Maznitsa.
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

package com.igormaznitsa.jbbp.compiler.conversion;

import com.igormaznitsa.jbbp.JBBPCustomFieldTypeProcessor;
import com.igormaznitsa.jbbp.JBBPParser;
import com.igormaznitsa.jbbp.compiler.JBBPNamedFieldInfo;
import com.igormaznitsa.jbbp.compiler.tokenizer.JBBPFieldTypeParameterContainer;
import com.igormaznitsa.jbbp.io.JBBPBitInputStream;
import com.igormaznitsa.jbbp.io.JBBPBitOrder;
import com.igormaznitsa.jbbp.model.JBBPAbstractField;
import com.igormaznitsa.jbbp.testaux.AbstractJavaClassCompilerTest;
import org.junit.Test;

import java.io.IOException;

public class ParserToJavaClassConverterCompilationTest extends AbstractJavaClassCompilerTest {

    private static final String PACKAGE_NAME = "com.igormaznitsa.test";
    private static final String CLASS_NAME = "TestClass";

    @Test
    public void testExpression() throws Exception {
        final JBBPParser parser = JBBPParser.prepare("bit:8 bitf; var somevar; bool bbb; long aaa; ubyte kkk; {{int lrn; {int [(lrn/aaa*1*(2*somevar-4)&$joomla)/(100%9>>bitf)&56|~kkk^78&bbb];}}}");
        final String classSrc = parser.makeClassSrc(PACKAGE_NAME, CLASS_NAME, "some multiline text\nto be added into header");
        System.out.println(classSrc);
        final ClassLoader cloader = saveAndCompile(new JavaClassContent(PACKAGE_NAME + '.' + CLASS_NAME, classSrc));
    }

    @Test
    public void testSinglePrimitiveNamedFields() throws Exception {
        final JBBPParser parser = JBBPParser.prepare("bit a;byte b;ubyte c;short d;ushort e;bool f;int g;long h;");
        final String classSrc = parser.makeClassSrc(PACKAGE_NAME, CLASS_NAME);
        System.out.println(classSrc);
        final ClassLoader cloader = saveAndCompile(new JavaClassContent(PACKAGE_NAME + '.' + CLASS_NAME, classSrc));
    }

    @Test
    public void testSinglePrimitiveAnonymousFields() throws Exception {
        final JBBPParser parser = JBBPParser.prepare("bit;byte;ubyte;short;ushort;bool;int;long;");
        final String classSrc = parser.makeClassSrc(PACKAGE_NAME, CLASS_NAME);
        System.out.println(classSrc);
        final ClassLoader cloader = saveAndCompile(new JavaClassContent(PACKAGE_NAME + '.' + CLASS_NAME, classSrc));
    }

    @Test
    public void testSinglePrimitiveAnonymousArrayFields() throws Exception {
        final JBBPParser parser = JBBPParser.prepare("bit[1];byte[2];ubyte[3];short[4];ushort[5];bool[6];int[7];long[8];");
        final String classSrc = parser.makeClassSrc(PACKAGE_NAME, CLASS_NAME);
        System.out.println(classSrc);
        final ClassLoader cloader = saveAndCompile(new JavaClassContent(PACKAGE_NAME + '.' + CLASS_NAME, classSrc));
    }

    @Test
    public void testActions() throws Exception {
        final JBBPParser parser = JBBPParser.prepare("reset$$;skip:8;align:22;");
        final String classSrc = parser.makeClassSrc(PACKAGE_NAME, CLASS_NAME);
        System.out.println(classSrc);
        final ClassLoader cloader = saveAndCompile(new JavaClassContent(PACKAGE_NAME + '.' + CLASS_NAME, classSrc));
    }

    @Test
    public void testStruct() throws Exception {
        final JBBPParser parser = JBBPParser.prepare("int;{byte;ubyte;{long;}}");
        final String classSrc = parser.makeClassSrc(PACKAGE_NAME, CLASS_NAME);
        System.out.println(classSrc);
        final ClassLoader cloader = saveAndCompile(new JavaClassContent(PACKAGE_NAME + '.' + CLASS_NAME, classSrc));
    }

    @Test
    public void testPrimitiveArrayInsideStructArray() throws Exception {
        final JBBPParser parser = JBBPParser.prepare("ubyte len; {ubyte[len];} ubyte [_] rest;");
        final String classSrc = parser.makeClassSrc(PACKAGE_NAME, CLASS_NAME);
        System.out.println(classSrc);
        final ClassLoader cloader = saveAndCompile(new JavaClassContent(PACKAGE_NAME + '.' + CLASS_NAME, classSrc));
    }

    @Test
    public void testExternalValueInExpression() throws Exception {
        final JBBPParser parser = JBBPParser.prepare("ubyte len; <int [len*2+$ex] hello;");
        final String classSrc = parser.makeClassSrc(PACKAGE_NAME, CLASS_NAME);
        System.out.println(classSrc);
        final ClassLoader cloader = saveAndCompile(new JavaClassContent(PACKAGE_NAME + '.' + CLASS_NAME, classSrc));
    }

    @Test
    public void testCustomType() throws Exception {
        final JBBPParser parser = JBBPParser.prepare("some alpha;", new JBBPCustomFieldTypeProcessor() {
            @Override
            public String[] getCustomFieldTypes() {
                return new String[]{"some"};
            }

            @Override
            public boolean isAllowed(JBBPFieldTypeParameterContainer fieldType, String fieldName, int extraData, boolean isArray) {
                return true;
            }

            @Override
            public JBBPAbstractField readCustomFieldType(JBBPBitInputStream in, JBBPBitOrder bitOrder, int parserFlags, JBBPFieldTypeParameterContainer customTypeFieldInfo, JBBPNamedFieldInfo fieldName, int extraData, boolean readWholeStream, int arrayLength) throws IOException {
                return null;
            }
        });

        final String classSrc = parser.makeClassSrc(PACKAGE_NAME, CLASS_NAME);
        System.out.println(classSrc);
        final ClassLoader cloader = saveAndCompile(new JavaClassContent(PACKAGE_NAME + '.' + CLASS_NAME, classSrc));
    }

    @Test
    public void testVarType() throws Exception {
        final JBBPParser parser = JBBPParser.prepare("var alpha; var [$$] beta;");
        final String classSrc = parser.makeClassSrc(PACKAGE_NAME, CLASS_NAME);
        System.out.println(classSrc);
        final ClassLoader cloader = saveAndCompile(new JavaClassContent(PACKAGE_NAME + '.' + CLASS_NAME, classSrc));
    }

    @Test
    public void testAllVariantsWithLinksToExternalStructures() throws Exception {
        final JBBPParser parser = JBBPParser.prepare("bit:1 bit1; bit:2 bit2; bit:3 bit3; bit:4 bit4; bit:5 bit5; bit:6 bit6; bit:7 bit7; bit:8 bit8;" +
                "byte alpha; ubyte beta; short gamma; ushort delta; bool epsilon; int teta; long longField; var varField;" +
                "struct1 { byte someByte; struct2 {bit:3 [34*someByte<<1+$ext] data;} }");
        final String classSrc = parser.makeClassSrc(PACKAGE_NAME, CLASS_NAME);
        System.out.println(classSrc);
        final ClassLoader cloader = saveAndCompile(new JavaClassContent(PACKAGE_NAME + '.' + CLASS_NAME, classSrc));
    }

    @Test
    public void testPngParsing() throws Exception {
        final JBBPParser pngParser = JBBPParser.prepare(
                "long header;"
                        + "// chunks\n"
                        + "chunk [_]{"
                        + "   int length; "
                        + "   int type; "
                        + "   byte[length] data; "
                        + "   int crc;"
                        + "}"
        );
        final String classSrc = pngParser.makeClassSrc(PACKAGE_NAME, CLASS_NAME);
        System.out.println(classSrc);
        final ClassLoader cloader = saveAndCompile(new JavaClassContent(PACKAGE_NAME + '.' + CLASS_NAME, classSrc));
    }

    @Test
    public void testAllTypes() throws Exception {
        final JBBPParser parser = JBBPParser.prepare("custom alpha; custom [123] beta; {{ var [alpha*$extr] variarr; var fld;}}", new JBBPCustomFieldTypeProcessor() {
            @Override
            public String[] getCustomFieldTypes() {
                return new String[]{"custom"};
            }

            @Override
            public boolean isAllowed(JBBPFieldTypeParameterContainer fieldType, String fieldName, int extraData, boolean isArray) {
                return true;
            }

            @Override
            public JBBPAbstractField readCustomFieldType(JBBPBitInputStream in, JBBPBitOrder bitOrder, int parserFlags, JBBPFieldTypeParameterContainer customTypeFieldInfo, JBBPNamedFieldInfo fieldName, int extraData, boolean readWholeStream, int arrayLength) throws IOException {
                return null;
            }
        });

        final String classSrc = parser.makeClassSrc(PACKAGE_NAME, CLASS_NAME);
        System.out.println(classSrc);
        final ClassLoader cloader = saveAndCompile(new JavaClassContent(PACKAGE_NAME + '.' + CLASS_NAME, classSrc));
    }


}