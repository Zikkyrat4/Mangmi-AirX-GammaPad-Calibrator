#include <stdio.h>
#include <assert.h>
#include <errno.h>
#define GP_HOST_TEST
#define __errno __errno_fake
#include "gpad.c"
int *__errno_fake(void) { return &errno; }
int main(void) {
    /* raw limits and center; stock GammaPad will subtract offset afterwards */
    cals[0]=(struct axis_cal){-20000,100,26000,110,3};
    assert(transform(0,-20000)==-32768+110);
    assert(transform(0,100)==110);
    assert(transform(0,26000)==32767+110);
    assert(transform(0,-9900)>-16450+110 && transform(0,-9900)<-16000+110);
    assert(transform(0,13100)>16000+110 && transform(0,13100)<16700+110);
    int last=-32768;
    for(int x=-23000; x<=30000; ++x) {
        int y=transform(0,x)-110;
        assert(y>=last && y>=-32768 && y<=32767);
        last=y;
    }
    assert(transform(1,900)==900); // not configured: unchanged
    puts("PASS: symmetric/asymmetric interpolation, clipping, monotonicity, fallback");
    return 0;
}
