/*******************************************************************************
 * Copyright (c) 2002 - 2006 IBM Corporation.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package common;

import java.util.Iterator;
import java.util.NoSuchElementException;

public class Pair<T, U> {

    public final T fst;
    public final U snd;

    protected Pair(T fst, U snd) {
        this.fst = fst;
        this.snd = snd;
    }

    private boolean check(Object x, Object y) {
        return (x == null) ? (y == null) : x.equals(y);
    }

    @SuppressWarnings("rawtypes")
    @Override
    public boolean equals(Object o) {
        return (o instanceof Pair) && check(fst, ((Pair) o).fst)
            && check(snd, ((Pair) o).snd);
    }

    private int hc(Object o) {
        return (o == null) ? 0 : o.hashCode();
    }

    @Override
    public int hashCode() {
        return hc(fst) * 7219 + hc(snd);
    }

    public Iterator<Object> iterator() {
        return new Iterator<Object>() {
            byte next = 1;

            @Override
            public boolean hasNext() {
                return next > 0;
            }

            @Override
            public Object next() {
                switch (next) {
                    case 1:
                        next++;
                        return fst;
                    case 2:
                        next = 0;
                        return snd;
                    default:
                        throw new NoSuchElementException();
                }
            }

            @Override
            public void remove() {
                assert false;
            }
        };
    }

    @Override
    public String toString() {
        return "[" + fst + "," + snd + "]";
    }

    public static <T, U> Pair<T, U> make(T x, U y) {
        return new Pair<T, U>(x, y);
    }
}
