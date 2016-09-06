#include <stdio.h>

/**
 * This is an example C program that can run as a native openwhisk
 * action using the openwhisk/dockerskeleton.
 *
 * The input to the action is received as an argument from the command line.
 * Actions may log to stdout or stderr.
 * By convention, the last line of output must be a stringified JSON object
 * which represents the result of the action.
 */

int main(int argc, char *argv[]) {
    printf("This is an example log message from an arbitrary C program!\n");
    printf("{ \"msg\": \"Hello from arbitrary C program!\", \"args\": %s }",
           (argc == 1) ? "undefined" : argv[1]);
}