/*

Copyright 2010, Google Inc.
All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are
met:

    * Redistributions of source code must retain the above copyright
notice, this list of conditions and the following disclaimer.
    * Redistributions in binary form must reproduce the above
copyright notice, this list of conditions and the following disclaimer
in the documentation and/or other materials provided with the
distribution.
    * Neither the name of Google Inc. nor the names of its
contributors may be used to endorse or promote products derived from
this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
"AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,           
DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY           
THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
(INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

*/

package com.google.refine.jython;

import java.io.File;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import org.python.core.Py;
import org.python.core.PyException;
import org.python.core.PyFloat;
import org.python.core.PyFunction;
import org.python.core.PyInteger;
import org.python.core.PyLong;
import org.python.core.PyNone;
import org.python.core.PyObject;
import org.python.core.PyString;
import org.python.core.PySyntaxError;
import org.python.util.PythonInterpreter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.refine.expr.EvalError;
import com.google.refine.expr.Evaluable;
import com.google.refine.expr.HasFields;
import com.google.refine.expr.LanguageSpecificParser;
import com.google.refine.expr.ParsingException;

public class JythonEvaluable implements Evaluable {

    final static Logger logger = LoggerFactory.getLogger("jython");

    static public LanguageSpecificParser createParser() {
        return new LanguageSpecificParser() {

            @Override
            public Evaluable parse(String source, String languagePrefix) throws ParsingException {
                try {
                    return new JythonEvaluable(source, languagePrefix);
                } catch (PySyntaxError e) {
                    throw new ParsingException("Syntax error");
                }
            }

        };
    }

    private final String s_functionName;
    private final String s_originalSource;
    private final String s_languagePrefix;

    private static PythonInterpreter _engine;

    // FIXME(SM): this initialization logic depends on the fact that the JVM's
    // current working directory is the root of the OpenRefine distributions
    // or the development checkouts. While this works in practice, it would
    // be preferable to have a more reliable address space, but since we
    // don't have access to the servlet context from this class this is
    // the best we can do for now.
    static {
        logger.debug("Executing static block in JythonEvaluable");
        File libPath = new File("webapp/WEB-INF/lib/jython");
        if (!libPath.exists() && !libPath.canRead()) {
            libPath = new File("main/webapp/WEB-INF/lib/jython");
            if (!libPath.exists() && !libPath.canRead()) {
                libPath = null;
            }
        }

        if (libPath != null) {
            Properties props = new Properties();
            props.setProperty("python.path", libPath.getAbsolutePath());
            PythonInterpreter.initialize(System.getProperties(), props, new String[] { "" });
        }

        logger.debug("Done with static block in Jython initialization");
    }

    // Convenience constructor for tests
    protected JythonEvaluable(String source) {
        this(source, "jython");
    }

    public JythonEvaluable(String source, String languagePrefix) {
        s_originalSource = source;
        s_languagePrefix = languagePrefix;
        if (_engine == null) {
            // TODO: This could potentially be done in the background, after startup, but before the user needs it
            logger.debug("Invoking constructor for PythonInterpreter()");
            _engine = new PythonInterpreter();
            logger.debug("Done constructor for PythonInterpreter()");
        }
        this.s_functionName = String.format("__temp_%d__", Math.abs(source.hashCode()));

        // indent and create a function out of the code
        String[] lines = source.split("\r\n|\r|\n");

        StringBuffer sb = new StringBuffer(1024);
        sb.append("def ");
        sb.append(s_functionName);
        sb.append("(value, cell, cells, row, rowIndex, value1, value2):");
        for (String line : lines) {
            sb.append("\n  ");
            sb.append(line);
        }

        _engine.exec(sb.toString());
    }

    @Override
    public Object evaluate(Properties bindings) {
        try {
            // call the temporary PyFunction directly
            Object result = ((PyFunction) _engine.get(s_functionName)).__call__(

                    new PyObject[] {
                            getValue("value", bindings),
                            getObject("cell", bindings),
                            getObject("cells", bindings),
                            getObject("row", bindings),
                            getValue("rowIndex", bindings),
                            getValue("value1", bindings),
                            getValue("value2", bindings)
                    });

            return unwrap(result);
        } catch (PyException e) {
            return new EvalError(e.getMessage());
        }
    }

    private JythonHasFieldsWrapper getObject(String key, Properties bindings) {
        return new JythonHasFieldsWrapper((HasFields) bindings.get(key), bindings);
    }

    private PyObject getValue(String key, Properties bindings) {
        Object value = bindings.get(key);
        PyObject pyValue;
        if (value instanceof OffsetDateTime) {
            // We know the OffsetDateTime is always at UTC, but just in case it ever
            // changes, do the UTC change
            pyValue = Py.newDatetime(
                    Timestamp.valueOf(((OffsetDateTime) value).atZoneSameInstant(ZoneOffset.UTC).toLocalDateTime()));
        } else {
            pyValue = Py.java2py(value);
        }
        return pyValue;
    }

    protected Object unwrap(Object result) {
        if (result != null) {
            if (result instanceof JythonObjectWrapper) {
                return ((JythonObjectWrapper) result)._obj;
            } else if (result instanceof JythonHasFieldsWrapper) {
                return ((JythonHasFieldsWrapper) result)._obj;
            } else if (result instanceof PyString) {
                return ((PyString) result).asString();
            } else if (result instanceof PyInteger) {
                return (long) ((PyInteger) result).asInt();
            } else if (result instanceof PyLong) {
                return ((PyLong) result).getLong(Long.MIN_VALUE, Long.MAX_VALUE);
            } else if (result instanceof PyFloat) {
                return ((PyFloat) result).asDouble();
            } else if (result instanceof PyObject) {
                return unwrap((PyObject) result);
            }
        }

        return result;
    }

    protected Object unwrap(PyObject po) {
        if (po instanceof PyNone) {
            return null;
        } else if ("datetime".equals(po.getType().getName())) {
            return OffsetDateTime.of(
                    po.__getattr__("year").asInt(),
                    po.__getattr__("month").asInt(),
                    po.__getattr__("day").asInt(),
                    po.__getattr__("hour").asInt(),
                    po.__getattr__("minute").asInt(),
                    po.__getattr__("second").asInt(),
                    po.__getattr__("microsecond").asInt() * 1000, // scale to nanoseconds
                    ZoneOffset.UTC);
        } else if (po.isNumberType()) {
            return po.asDouble();
        } else if (po.isSequenceType()) {
            List<Object> list = new ArrayList<>();
            for (Object o : po.asIterable()) {
                list.add(unwrap(o));
            }
            return list.toArray();
        } else {
            return po;
        }
    }

    @Override
    public String getSource() {
        return s_originalSource;
    }

    @Override
    public String getLanguagePrefix() {
        return s_languagePrefix;
    }
}
