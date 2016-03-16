package openwhisk.java.action;

import java.security.Permission;

/**
 * A `SecurityManager` installed when executing action code. The purpose here
 * is not so much to prevent malicious behavior than it is to prevent users from
 * shooting themselves in the foot. In particular, anything that kills the JVM
 * will result in unhelpful action error messages.
 */
public class WhiskSecurityManager extends SecurityManager {
    @Override
    public void checkPermission(Permission p) {
        // Not throwing means accepting anything.
    }

    @Override
    public void checkPermission(Permission p, Object ctx) {
        // Not throwing means accepting anything.
    }

    @Override
    public void checkExit(int status) {
        super.checkExit(status);
        throw new SecurityException("System.exit(" + status + ") called from within an action.");
    }
}
