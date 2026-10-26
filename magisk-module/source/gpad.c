/*
 * Mangmi Air X / GammaOS Next 1.2 experimental per-direction analog normalization.
 * ARM64 Android Bionic library interposing the GammaPad read() import on AIR X Controller.
 * No libc dependencies besides the system's existing __errno symbol.
 * Fallback: missing/invalid calibration == unmodified native input events.
 * License: MIT. NOT vendor-official.
 */
#include <stdint.h>
#include <stddef.h>

#define SYS_READ 63
#define SYS_OPENAT 56
#define SYS_CLOSE 57
#define SYS_IOCTL 29
#define AT_FDCWD -100
#define O_RDONLY 0
#define EV_ABS 3
#define EVIOCGNAME64 0x80404506UL

struct event { int64_t tv_sec; int64_t tv_usec; uint16_t type; uint16_t code; int32_t value; };
struct axis_cal { int32_t lo, center, hi, offset; int valid; };
static struct axis_cal cals[4];
static volatile int config_loaded;
static const uint16_t axes[4] = {0,1,2,5};
static const char cfg_path[] = "/data/GammaPad/RANGE_CALIBRATION";
static const char offset_path[] = "/data/GammaPad/CALIBRATION_DATA";

/* Direct Linux ARM64 system calls: bypass interposed read() safely. */
#ifdef GP_HOST_TEST
static long sys3(long nr, long a0, long a1, long a2) { (void)nr;(void)a0;(void)a1;(void)a2;return -1; }
static long sys4(long nr, long a0, long a1, long a2, long a3) { (void)nr;(void)a0;(void)a1;(void)a2;(void)a3;return -1; }
#else
static long sys3(long nr, long a0, long a1, long a2) {
    register long x8 __asm__("x8") = nr;
    register long x0 __asm__("x0") = a0;
    register long x1 __asm__("x1") = a1;
    register long x2 __asm__("x2") = a2;
    __asm__ volatile ("svc #0" : "+r"(x0) : "r"(x1),"r"(x2),"r"(x8) : "memory", "cc");
    return x0;
}
static long sys4(long nr, long a0, long a1, long a2, long a3) {
    register long x8 __asm__("x8") = nr;
    register long x0 __asm__("x0") = a0;
    register long x1 __asm__("x1") = a1;
    register long x2 __asm__("x2") = a2;
    register long x3 __asm__("x3") = a3;
    __asm__ volatile ("svc #0" : "+r"(x0) : "r"(x1),"r"(x2),"r"(x3),"r"(x8) : "memory", "cc");
    return x0;
}
#endif
_Static_assert(sizeof(struct event)==24, "Linux input_event must be 24 bytes on ARM64");
static int eq_prefix(const char *a, const char *b) {
    while (*b) if (*a++ != *b++) return 0;
    return 1;
}
static int axis_idx(int sc) {
    for (int i=0;i<4;i++) if (axes[i]==sc) return i;
    return -1;
}
/* Parses an entire line of 4 signed decimal integers, optionally with comments. */
static int numbers(const char *s, long out[], int limit) {
    int n=0;
    while (*s && n<limit) {
        while (*s==' ' || *s=='\t' || *s=='\r') s++;
        if (*s=='#' || *s=='\n' || *s==0) break;
        int neg=0; if (*s=='-' || *s=='+') {neg=(*s=='-');s++;}
        if (*s<'0'||*s>'9') return -1;
        long v=0;
        while (*s>='0'&&*s<='9') {
            v=v*10+(*s++-'0');
            if (v>100000000L) return -1;
        }
        out[n++]=neg?-v:v;
        if (*s!=' '&&*s!='\t'&&*s!='\n'&&*s!='\r'&&*s!='#'&&*s!=0) return -1;
    }
    return n;
}
static long read_file(const char *path, char *buf, long cap) {
    long fd=sys4(SYS_OPENAT,AT_FDCWD,(long)path,O_RDONLY,0);
    if (fd<0) return -1;
    long n=sys3(SYS_READ,fd,(long)buf,cap-1);
    sys3(SYS_CLOSE,fd,0,0);
    if (n<=0||n>=cap) return -1;
    buf[n]=0;
    return n;
}
static void parse_file(const char *path, int offsets) {
    char buf[1024];
    long n=read_file(path,buf,sizeof(buf));
    if (n<0) return;
    const char *p=buf;
    while (*p) {
        char line[100]; int ix=0;
        while (*p && *p!='\n') {
            if(ix < 99) line[ix++]=*p;
            p++;
        }
        if(*p=='\n')p++;
        line[ix]=0;
        long vals[4];
        int got=numbers(line,vals, offsets ? 2:4);
        if (got != (offsets ? 2:4)) continue;
        int j=axis_idx((int)vals[0]);
        if(j<0)continue;
        if(offsets) {
            if(vals[1] >= -32768 && vals[1]<=32767) {
                cals[j].offset=(int32_t)vals[1];
                /* Don't set valid based on offset alone. */
                cals[j].valid |= 2;
            }
        } else {
            long lo=vals[1],md=vals[2],hi=vals[3];
            if(lo < -32768 || hi > 32767 || lo >= md || md >= hi) continue;
            if(md-lo < 4096 || hi-md < 4096)continue;
            cals[j].lo=(int32_t)lo; cals[j].center=(int32_t)md; cals[j].hi=(int32_t)hi;
            cals[j].valid |= 1;
        }
    }
}
static void init_config(void) {
    /* A single event-consumer thread handles reads in GammaPad. */
    if (__atomic_load_n(&config_loaded,__ATOMIC_ACQUIRE)) return;
    parse_file(cfg_path,0);
    parse_file(offset_path,1);
    __atomic_store_n(&config_loaded,1,__ATOMIC_RELEASE);
}
static int32_t transform(int code, int32_t raw) {
    int i=axis_idx(code);
    if(i<0 || cals[i].valid != 3)return raw;
    const struct axis_cal *c=&cals[i];
    int64_t desired=0;
    if(raw<c->center) {
        int64_t d=(int64_t)c->center-raw;
        int64_t span=(int64_t)c->center-c->lo;
        desired=-((d*32768+span/2)/span);
        if(desired < -32768)desired=-32768;
    } else if(raw>c->center) {
        int64_t d=(int64_t)raw-c->center;
        int64_t span=(int64_t)c->hi-c->center;
        desired=(d*32767+span/2)/span;
        if(desired > 32767)desired=32767;
    }
    /* Stock GammaPad applyCalibration subtracts calibrationOffset. */
    return (int32_t)(desired + c->offset);
}
/* __errno is the Bionic TLS errno accessor, provided by the original libc.so. */
extern int *__errno(void);
__attribute__((visibility("default"))) long gpr1(int fd, void *buf, unsigned long count) {
    long n=sys3(SYS_READ,fd,(long)buf,(long)count);
    if(n<0) { if(n>=-4095){ *__errno()=(int)-n; return -1;} return n; }
    if(n<(long)sizeof(struct event) || (n%(long)sizeof(struct event))!=0) return n;
    /* ioctl verifies the FD actually is the built-in pad, not some other fd. */
    char name[64]={0};
    long rc=sys3(SYS_IOCTL,fd,EVIOCGNAME64,(long)name);
    if(rc<0 || !eq_prefix(name,"AIR X Controller")) return n;
    init_config();
    struct event *ev=(struct event *)buf;
    long total=n/(long)sizeof(*ev);
    for(long k=0;k<total;k++) {
        if(ev[k].type==EV_ABS && axis_idx(ev[k].code)>=0)
            ev[k].value=transform(ev[k].code,ev[k].value);
    }
    return n;
}
