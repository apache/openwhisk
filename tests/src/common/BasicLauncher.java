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

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.util.logging.Logger;

/**
 * A generic process launcher.
 *
 * MODIFIED so that the cmd is stored as a single string or as an array. In case
 * arguments contains spaces, the latter must be used because the former form
 * does not undestand quoting.
 */
public class BasicLauncher extends Launcher {

    protected String[] cmds;

    public BasicLauncher(boolean captureOutput, boolean captureErr, Logger logger) {
        super(captureOutput, captureErr, logger);
    }

    public String getCmd() {
        StringBuilder sb = new StringBuilder();
        String sep = "";
        for (String s : cmds) {
            sb.append(sep);
            sb.append(s);
            sep = " ";
        }
        return sb.toString();
    }

    public void setCmd(String... newCmds) {
        cmds = newCmds;
    }

    @Override
    public String toString() {
        StringBuffer result = new StringBuffer(super.toString());
        result.append(" (cmd: ");
        result.append(getCmd());
        return result.toString();
    }

    /*
     * Launch with no timeout.
     */
    public int launch() throws IllegalArgumentException, IOException {
        return launch(0);
    }

    /**
     * Launch the process and wait until it is finished. Returns the exit value
     * of the process.  Timeout is expressed in milli-seconds and a value of 0
     * indicates no timeout.
     */
    public int launch(int timeoutMilli) throws IllegalArgumentException, IOException {
        Process p = spawnProcess(cmds);
        Thread d1 = isCaptureErr() ? captureStdErr(p) : drainStdErr(p);
        Thread d2 = isCaptureOutput() ? captureStdOut(p) : drainStdOut(p);
        if (getInput() != null) {
            final BufferedOutputStream input = new BufferedOutputStream(p.getOutputStream());
            try {
                input.write(getInput(), 0, getInput().length);
                input.flush();
                input.close();
            } catch (IOException e) {
                e.printStackTrace();
                throw new IOException("error priming stdin", e);
            }
        }
        try {
            if (timeoutMilli == 0) {
                d1.join();
                d2.join();
            } else {
                d1.join(timeoutMilli);
                d2.join(timeoutMilli);
            }
        } catch (InterruptedException e) {
            throw new Error("Internal error", e);
        }
        if (isCaptureErr()) {
            Drainer d = (Drainer) d1;
            setStdErr(d.getCapture().toByteArray());
        }
        if (isCaptureOutput()) {
            Drainer d = (Drainer) d2;
            setStdOut(d.getCapture().toByteArray());
        }
        return p.exitValue();
    }
}
