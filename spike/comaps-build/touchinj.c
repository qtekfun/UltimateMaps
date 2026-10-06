// Inyector de gestos en el dispositivo (temporizacion local, sin jitter de adb inalambrico).
// Compilar: $NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android30-clang -O2 touchinj.c -o touchinj -lm
// Uso: touchinj <pan|zoom|rotate> <segundos> [hz=120]    escribe en /dev/input/event3 (goodix_ts0, protocolo B)
#include <fcntl.h>
#include <linux/input.h>
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

static int fd;
static void ev(int t, int c, int v) {
  struct input_event e;
  memset(&e, 0, sizeof e);
  e.type = t; e.code = c; e.value = v;
  if (write(fd, &e, sizeof e) < 0) perror("write");
}
static void syn(void) { ev(EV_SYN, SYN_REPORT, 0); }
static int tid = 200;
static void down(int slot, int x, int y) {
  ev(EV_ABS, ABS_MT_SLOT, slot); ev(EV_ABS, ABS_MT_TRACKING_ID, tid++);
  ev(EV_ABS, ABS_MT_POSITION_X, x); ev(EV_ABS, ABS_MT_POSITION_Y, y);
  ev(EV_ABS, ABS_MT_PRESSURE, 60); ev(EV_ABS, ABS_MT_TOUCH_MAJOR, 10);
}
static void mv(int slot, double x, double y) {
  ev(EV_ABS, ABS_MT_SLOT, slot); ev(EV_ABS, ABS_MT_POSITION_X, (int)x); ev(EV_ABS, ABS_MT_POSITION_Y, (int)y);
}
static void up(int slot) { ev(EV_ABS, ABS_MT_SLOT, slot); ev(EV_ABS, ABS_MT_TRACKING_ID, -1); }
static void tsleep_until(struct timespec *t) { clock_nanosleep(CLOCK_MONOTONIC, TIMER_ABSTIME, t, NULL); }

int main(int argc, char **argv) {
  if (argc < 3) return 1;
  const char *k = argv[1];
  double secs = atof(argv[2]);
  double hz = argc > 3 ? atof(argv[3]) : 120.0;
  fd = open("/dev/input/event3", O_WRONLY);
  if (fd < 0) { perror("open"); return 2; }
  const double CX = 540, CY = 1250, dt = 1.0 / hz;
  int n = (int)(secs * hz);
  int two = strcmp(k, "pan") != 0;
  if (!two) { down(0, CX, CY); ev(EV_KEY, BTN_TOUCH, 1); }
  else { down(0, CX - 60, CY); down(1, CX + 60, CY); ev(EV_KEY, BTN_TOUCH, 1); }
  syn();
  struct timespec t0; clock_gettime(CLOCK_MONOTONIC, &t0);
  for (int i = 0; i < n; i++) {
    double t = i * dt;
    if (!strcmp(k, "pan")) {
      double ph = fmod(t, 1.0);
      mv(0, CX + 200 * sin(M_PI * ph), CY + 500 * sin(2 * M_PI * ph));
    } else if (!strcmp(k, "zoom")) {
      double ph = fmod(t, 2.0) / 2.0;
      double d = 120 + 480 * (0.5 - 0.5 * cos(2 * M_PI * ph));
      mv(0, CX - d / 2, CY); mv(1, CX + d / 2, CY);
    } else {  // rotate: 360 grados cada 3 s, radio 250 px
      double a = 2 * M_PI * fmod(t, 3.0) / 3.0, r = 250;
      mv(0, CX - r * cos(a), CY - r * sin(a)); mv(1, CX + r * cos(a), CY + r * sin(a));
    }
    syn();
    struct timespec nx = t0;
    double s = (i + 1) * dt;
    nx.tv_sec += (time_t)s; nx.tv_nsec += (long)((s - (time_t)s) * 1e9);
    if (nx.tv_nsec >= 1000000000L) { nx.tv_sec++; nx.tv_nsec -= 1000000000L; }
    tsleep_until(&nx);
  }
  up(0); if (two) up(1);
  ev(EV_KEY, BTN_TOUCH, 0); syn();
  close(fd);
  return 0;
}
