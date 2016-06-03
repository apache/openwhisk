#include <stdio.h>

int main(int argc, char *argv[]) {
    printf("Hello %s from arbitrary C program!\n",
           (argc == 1) ? "anonymous" : argv[1]);
}
