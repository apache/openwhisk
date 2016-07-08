#include <stdio.h>

int main(int argc, char *argv[]) {
    printf("{ \"msg\": \"Hello from arbitrary C program!\", \"args\": %s }",
           (argc == 1) ? "undefined" : argv[1]);
}