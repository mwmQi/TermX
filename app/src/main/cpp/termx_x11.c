/**
 * TermX X11 Virtual Framebuffer Server
 *
 * A minimal Xvfb-like X11 server that provides a virtual display for
 * GUI applications running inside TermX. This allows headless rendering
 * of graphical apps (like browsers for Cloudflare CAPTCHA solving) without
 * requiring physical display drivers.
 *
 * Architecture:
 *   - Listens on TCP port 6000+displayNum for X11 client connections
 *   - Maintains a virtual framebuffer (RGBA pixel buffer) in shared memory
 *   - Processes X11 core protocol requests from connected clients
 *   - Delivers input events (keyboard/mouse) to connected clients
 *   - Supports multiple display sessions (:0, :1, :2, etc.)
 *
 * Build: Android NDK with CMake, linked against libandroid and liblog
 */

#include <jni.h>
#include <android/log.h>
#include <stdlib.h>
#include <string.h>
#include <stdio.h>
#include <unistd.h>
#include <pthread.h>
#include <sys/socket.h>
#include <sys/select.h>
#include <sys/shm.h>
#include <sys/stat.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <errno.h>
#include <fcntl.h>
#include <signal.h>

#define TAG "TermX-X11"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)

// ---- X11 Protocol Constants ----

#define X11_PROTOCOL_MAJOR  11
#define X11_PROTOCOL_MINOR  0

// X11 request opcodes
#define X_CreateWindow          1
#define X_ChangeWindowAttributes 2
#define X_GetWindowAttributes   3
#define X_DestroyWindow         4
#define X_DestroySubwindows     5
#define X_ChangeSaveSet         6
#define X_ReparentWindow        7
#define X_MapWindow             8
#define X_MapSubwindows         9
#define X_UnmapWindow          10
#define X_UnmapSubwindows      11
#define X_ConfigureWindow      12
#define X_CirculateWindow      13
#define X_GetGeometry          14
#define X_QueryTree            15
#define X_InternAtom           16
#define X_GetAtomName          17
#define X_ChangeProperty       18
#define X_DeleteProperty       19
#define X_GetProperty          20
#define X_ListProperties       21
#define X_SetSelectionOwner    22
#define X_GetSelectionOwner    23
#define X_ConvertSelection     24
#define X_SendEvent            25
#define X_GrabPointer          26
#define X_UngrabPointer        27
#define X_GrabButton           28
#define X_UngrabButton         29
#define X_ChangeActivePointerGrab 30
#define X_GrabKeyboard         31
#define X_UngrabKeyboard       32
#define X_GrabKey              33
#define X_UngrabKey            34
#define X_AllowEvents          35
#define X_GrabServer           36
#define X_UngrabServer         37
#define X_QueryPointer         38
#define X_GetMotionEvents      39
#define X_TranslateCoords      40
#define X_WarpPointer          41
#define X_SetInputFocus        42
#define X_GetInputFocus        43
#define X_QueryKeymap          44
#define X_OpenFont             45
#define X_CloseFont            46
#define X_QueryFont            47
#define X_QueryTextExtents     48
#define X_ListFonts            49
#define X_ListFontsWithInfo    50
#define X_SetFontPath          51
#define X_GetFontPath          52
#define X_CreatePixmap         53
#define X_FreePixmap           54
#define X_CreateGC             55
#define X_ChangeGC             56
#define X_CopyGC               57
#define X_SetDashes            58
#define X_SetClipRectangles    59
#define X_FreeGC               60
#define X_ClearArea            61
#define X_CopyArea             62
#define X_CopyPlane            63
#define X_PolyPoint            64
#define X_PolyLine             65
#define X_PolySegment          66
#define X_PolyRectangle        67
#define X_PolyArc              68
#define X_FillPoly             69
#define X_PolyFillRectangle    70
#define X_PolyFillArc          71
#define X_PutImage             72
#define X_GetImage             73
#define X_PolyText8            74
#define X_PolyText16           75
#define X_ImageText8           76
#define X_ImageText16          77
#define X_CreateColormap       78
#define X_FreeColormap         79
#define X_CopyColormapAndFree  80
#define X_InstallColormap      81
#define X_UninstallColormap    82
#define X_ListInstalledColormaps 83
#define X_AllocColor           84
#define X_AllocNamedColor      85
#define X_AllocColorCells      86
#define X_AllocColorPlanes     87
#define X_FreeColors           88
#define X_StoreColors          89
#define X_StoreNamedColor      90
#define X_QueryColors          91
#define X_LookupColor          92
#define X_CreateCursor         93
#define X_CreateGlyphCursor    94
#define X_FreeCursor           95
#define X_RecolorCursor        96
#define X_QueryBestSize        97
#define X_QueryExtension       98
#define X_ListExtensions       99
#define X_ChangeKeyboardMapping 100
#define X_GetKeyboardMapping   101
#define X_ChangeKeyboardControl 102
#define X_GetKeyboardControl   103
#define X_Bell                 104
#define X_ChangePointerControl 105
#define X_GetPointerControl    106
#define X_SetScreenSaver       107
#define X_GetScreenSaver       108
#define X_ChangeHosts          109
#define X_ListHosts            110
#define X_SetAccessControl     111
#define X_SetCloseDownMode     112
#define X_KillClient           113
#define X_RotateProperties     114
#define X_ForceScreenSaver     115
#define X_SetPointerMapping    116
#define X_GetPointerMapping    117
#define X_SetModifierMapping   118
#define X_GetModifierMapping   119
#define X_NoOperation          127

// X11 event codes
#define X_Event_KeyPress        2
#define X_Event_KeyRelease      3
#define X_Event_ButtonPress     4
#define X_Event_ButtonRelease   5
#define X_Event_MotionNotify    6
#define X_Event_EnterNotify     7
#define X_Event_LeaveNotify     8
#define X_Event_FocusIn         9
#define X_Event_FocusOut       10
#define X_Event_KeymapNotify   11
#define X_Event_Expose         12
#define X_Event_GraphicsExposure 13
#define X_Event_NoExposure     14
#define X_Event_VisibilityNotify 15
#define X_Event_CreateNotify   16
#define X_Event_DestroyNotify  17
#define X_Event_UnmapNotify    18
#define X_Event_MapNotify      19
#define X_Event_MapRequest     20
#define X_Event_ReparentNotify 21
#define X_Event_ConfigureNotify 22
#define X_Event_ConfigureRequest 23
#define X_Event_GravityNotify  24
#define X_Event_ResizeRequest  25
#define X_Event_CirculateNotify 26
#define X_Event_PropertyNotify 28
#define X_Event_SelectionClear 29
#define X_Event_SelectionRequest 30
#define X_Event_SelectionNotify 31
#define X_Event_ClientMessage  33
#define X_Event_MappingNotify  34

// X11 error codes
#define X_Error_Request     1
#define X_Error_Value       2
#define X_Error_Window      3
#define X_Error_Pixmap      4
#define X_Error_Atom        5
#define X_Error_Cursor      6
#define X_Error_Font        7
#define X_Error_Match       8
#define X_Error_Drawable    9
#define X_Error_Access     10
#define X_Error_Alloc      11
#define X_Error_Colormap   12
#define X_Error_GContext   13
#define X_Error_IDChoice   14
#define X_Error_Name       15
#define X_Error_Length     16
#define X_Error_Implementation 17

// X11 image formats
#define X_ImageFormat_XYBitmap  0
#define X_ImageFormat_XYPixmap  1
#define X_ImageFormat_ZPixmap   2

// Predefined atoms
#define XA_PRIMARY           1
#define XA_SECONDARY         2
#define XA_ARC               3
#define XA_ATOM               4
#define XA_BITMAP             5
#define XA_CARDINAL           6
#define XA_COLORMAP           7
#define XA_CURSOR             8
#define XA_CUT_BUFFER0        9
#define XA_DRAWABLE          17
#define XA_FONT              18
#define XA_INTEGER           19
#define XA_PIXMAP            20
#define XA_POINT             21
#define XA_RECTANGLE         22
#define XA_RESOURCE_MANAGER  23
#define XA_RGB_COLOR_MAP     24
#define XA_RGB_BEST_MAP      25
#define XA_STRING            31
#define XA_VISUALID          32
#define XA_WINDOW            33
#define XA_WM_COMMAND        34
#define XA_WM_HINTS          35
#define XA_WM_CLIENT_MACHINE 36
#define XA_WM_ICON_NAME      37
#define XA_WM_ICON_SIZE      38
#define XA_WM_NAME           39
#define XA_WM_NORMAL_HINTS   40
#define XA_WM_SIZE_HINTS     41
#define XA_WM_ZOOM_HINTS     42
#define XA_MIN_SPACE         43
#define XA_NORM_SPACE        44
#define XA_MAX_SPACE         45
#define XA_END_SPACE         46
#define XA_SUPERSCRIPT_X     47
#define XA_SUPERSCRIPT_Y     48
#define XA_SUBSCRIPT_X       49
#define XA_SUBSCRIPT_Y       50
#define XA_UNDERLINE_POSITION 51
#define XA_UNDERLINE_THICKNESS 52
#define XA_STRIKEOUT_ASCENT  53
#define XA_STRIKEOUT_DESCENT 54
#define XA_ITALIC_ANGLE      55
#define XA_X_HEIGHT          56
#define XA_QUAD_WIDTH        57
#define XA_WEIGHT            58
#define XA_POINT_SIZE        59
#define XA_RESOLUTION        60
#define XA_COPYRIGHT         61
#define XA_NOTICE            62
#define XA_FONT_NAME         63
#define XA_FAMILY_NAME       64
#define XA_FULL_NAME         65
#define XA_CAP_HEIGHT        66
#define XA_WM_CLASS          67
#define XA_WM_TRANSIENT_FOR  68

// ---- Server State ----

#define MAX_CLIENTS       16
#define MAX_WINDOWS      256
#define MAX_ATOMS        512
#define MAX_GC           64
#define MAX_PIXMAPS      64
#define MAX_PROPERTIES   512
#define MAX_WIDTH        4096
#define MAX_HEIGHT       4096
#define READ_BUF_SIZE    65536

typedef struct {
    int x, y, w, h;
    int mapped;
    int override_redirect;
    int bg_pixel;
    int event_mask;
    int xclass;  /* InputOutput=1, InputOnly=2 */
    int depth;
    int parent;
} XWindow;

typedef struct {
    int id;
    int clip_x1, clip_y1, clip_x2, clip_y2;
    int fg_pixel, bg_pixel;
    int function;  /* GXcopy, etc. */
    int plane_mask;
    int line_width;
    int line_style;
    int fill_style;
    int fill_rule;
    int tile;
    int stipple;
    int ts_x_origin, ts_y_origin;
    int font;
    int subwindow_mode;
    int graphics_exposures;
    int clip_x_origin, clip_y_origin;
    int dash_offset;
    char dashes;
} XGC;

typedef struct {
    int id;
    int width, height, depth;
    unsigned char *data;  /* pixel data */
} XPixmap;

typedef struct {
    int atom;
    int window;
    char name[256];
    int type;        /* atom type */
    int format;      /* 8, 16, 32 */
    int data_len;
    unsigned char data[4096];
} XProperty;

typedef struct {
    char name[64];
    int atom;
} XAtomEntry;

typedef struct {
    int fd;
    int id;
    int byte_order;  /* 0=LSB, 1=MSB */
    int connected;
    pthread_t thread;
    struct X11Server *server;
    int sequence;  /* client sequence number */
} XClient;

typedef struct X11Server {
    int display_num;
    int width;
    int height;
    int port;
    int running;
    int server_fd;

    /* Virtual framebuffer */
    unsigned char *framebuffer;
    int fb_size;
    int fb_shmid;

    /* Dirty region tracking */
    int dirty_x, dirty_y, dirty_w, dirty_h;
    int has_dirty;

    /* X11 resources */
    XWindow windows[MAX_WINDOWS];
    int window_count;
    XGC gcs[MAX_GC];
    int gc_count;
    XPixmap pixmaps[MAX_PIXMAPS];
    int pixmap_count;
    XProperty properties[MAX_PROPERTIES];
    int property_count;
    XAtomEntry atoms[MAX_ATOMS];
    int atom_count;
    int next_resource_id;

    /* Client connections */
    XClient clients[MAX_CLIENTS];
    int client_count;

    /* Input state */
    int mouse_x, mouse_y;
    int mouse_buttons;
    int keyboard_state[32];  /* 256 bits */

    /* Threading */
    pthread_t accept_thread;
    pthread_mutex_t lock;

    /* Root window */
    int root_window;
    int root_visual;
} X11Server;

// ---- Global server instances (support multiple displays) ----

#define MAX_DISPLAYS 8
static X11Server *g_servers[MAX_DISPLAYS] = {NULL};
static pthread_mutex_t g_global_lock = PTHREAD_MUTEX_INITIALIZER;

// ---- Utility Functions ----

static int read_exact(int fd, void *buf, size_t len) {
    size_t done = 0;
    while (done < len) {
        ssize_t n = read(fd, (char*)buf + done, len - done);
        if (n <= 0) return -1;
        done += n;
    }
    return 0;
}

static int write_exact(int fd, const void *buf, size_t len) {
    size_t done = 0;
    while (done < len) {
        ssize_t n = write(fd, (const char*)buf + done, len - done);
        if (n <= 0) return -1;
        done += n;
    }
    return 0;
}

/* Read a CARD16 respecting byte order */
static uint16_t read_card16(XClient *client, const unsigned char *buf) {
    if (client->byte_order == 0) {  /* LSB */
        return buf[0] | (buf[1] << 8);
    } else {
        return (buf[0] << 8) | buf[1];
    }
}

static uint32_t read_card32(XClient *client, const unsigned char *buf) {
    if (client->byte_order == 0) {
        return buf[0] | (buf[1] << 8) | (buf[2] << 16) | (buf[3] << 24);
    } else {
        return (buf[0] << 24) | (buf[1] << 16) | (buf[2] << 8) | buf[3];
    }
}

static void write_card16(unsigned char *buf, uint16_t val, int byte_order) {
    if (byte_order == 0) {
        buf[0] = val & 0xFF;
        buf[1] = (val >> 8) & 0xFF;
    } else {
        buf[0] = (val >> 8) & 0xFF;
        buf[1] = val & 0xFF;
    }
}

static void write_card32(unsigned char *buf, uint32_t val, int byte_order) {
    if (byte_order == 0) {
        buf[0] = val & 0xFF;
        buf[1] = (val >> 8) & 0xFF;
        buf[2] = (val >> 16) & 0xFF;
        buf[3] = (val >> 24) & 0xFF;
    } else {
        buf[0] = (val >> 24) & 0xFF;
        buf[1] = (val >> 16) & 0xFF;
        buf[2] = (val >> 8) & 0xFF;
        buf[3] = val & 0xFF;
    }
}

/* Allocate a new X11 resource ID */
static uint32_t alloc_resource_id(X11Server *server) {
    return ++server->next_resource_id;
}

/* Find a window by ID */
static XWindow *find_window(X11Server *server, uint32_t id) {
    for (int i = 0; i < server->window_count; i++) {
        if ((uint32_t)(i + 1) == id || server->windows[i].parent == (int)id) {
            /* Check if this is a direct ID match */
        }
    }
    /* Windows are stored with ID = index+1 */
    if (id > 0 && id <= (uint32_t)server->window_count) {
        return &server->windows[id - 1];
    }
    /* Also check root window */
    if (id == (uint32_t)server->root_window) {
        return &server->windows[0];
    }
    return NULL;
}

/* Find a GC by ID */
static XGC *find_gc(X11Server *server, uint32_t id) {
    for (int i = 0; i < server->gc_count; i++) {
        if (server->gcs[i].id == (int)id) return &server->gcs[i];
    }
    return NULL;
}

/* Find a pixmap by ID */
static XPixmap *find_pixmap(X11Server *server, uint32_t id) {
    for (int i = 0; i < server->pixmap_count; i++) {
        if (server->pixmaps[i].id == (int)id) return &server->pixmaps[i];
    }
    return NULL;
}

/* Find or create an atom */
static int intern_atom(X11Server *server, const char *name, int only_if_exists) {
    for (int i = 0; i < server->atom_count; i++) {
        if (strcmp(server->atoms[i].name, name) == 0) return server->atoms[i].atom;
    }
    if (only_if_exists) return 0;
    if (server->atom_count >= MAX_ATOMS) return 0;
    int atom = 68 + server->atom_count + 1;  /* Start after predefined atoms */
    strncpy(server->atoms[server->atom_count].name, name, 63);
    server->atoms[server->atom_count].name[63] = 0;
    server->atoms[server->atom_count].atom = atom;
    server->atom_count++;
    return atom;
}

/* Mark a region dirty in the framebuffer */
static void mark_dirty(X11Server *server, int x, int y, int w, int h) {
    if (!server->has_dirty) {
        server->dirty_x = x;
        server->dirty_y = y;
        server->dirty_w = w;
        server->dirty_h = h;
        server->has_dirty = 1;
    } else {
        int nx = server->dirty_x < x ? server->dirty_x : x;
        int ny = server->dirty_y < y ? server->dirty_y : y;
        int rx = (server->dirty_x + server->dirty_w) > (x + w) ?
                 (server->dirty_x + server->dirty_w) : (x + w);
        int ry = (server->dirty_y + server->dirty_h) > (y + h) ?
                 (server->dirty_y + server->dirty_h) : (y + h);
        server->dirty_x = nx;
        server->dirty_y = ny;
        server->dirty_w = rx - nx;
        server->dirty_h = ry - ny;
    }
}

/* Draw a pixel to the framebuffer */
static void fb_put_pixel(X11Server *server, int x, int y, uint32_t color) {
    if (x < 0 || x >= server->width || y < 0 || y >= server->height) return;
    uint32_t *fb = (uint32_t *)server->framebuffer;
    fb[y * server->width + x] = color;
}

/* Draw a filled rectangle */
static void fb_fill_rect(X11Server *server, int x, int y, int w, int h, uint32_t color) {
    if (x >= server->width || y >= server->height) return;
    int x2 = x + w; if (x2 > server->width) x2 = server->width;
    int y2 = y + h; if (y2 > server->height) y2 = server->height;
    if (x < 0) x = 0; if (y < 0) y = 0;
    uint32_t *fb = (uint32_t *)server->framebuffer;
    for (int py = y; py < y2; py++) {
        for (int px = x; px < x2; px++) {
            fb[py * server->width + px] = color;
        }
    }
    mark_dirty(server, x, y, x2 - x, y2 - y);
}

/* Write raw image data to framebuffer */
static void fb_put_image(X11Server *server, int dst_x, int dst_y,
                         int w, int h, const unsigned char *data, int src_stride,
                         int format, int depth, int byte_order) {
    if (dst_x >= server->width || dst_y >= server->height) return;
    int x2 = dst_x + w; if (x2 > server->width) x2 = server->width;
    int y2 = dst_y + h; if (y2 > server->height) y2 = server->height;
    int clip_w = x2 - dst_x;
    int clip_h = y2 - dst_y;

    uint32_t *fb = (uint32_t *)server->framebuffer;

    if (format == X_ImageFormat_ZPixmap && depth == 24) {
        /* 24-bit ZPixmap: 3 bytes per pixel (BGR or RGB depending on byte order) */
        for (int py = 0; py < clip_h; py++) {
            const unsigned char *row = data + py * src_stride;
            for (int px = 0; px < clip_w; px++) {
                int idx = px * (depth > 16 ? 4 : (depth > 8 ? 3 : 1));
                if (idx + 2 >= src_stride && px == clip_w - 1) break;
                uint8_t b = row[idx];
                uint8_t g = row[idx + 1];
                uint8_t r = row[idx + 2];
                fb[(dst_y + py) * server->width + (dst_x + px)] = 0xFF000000 | (r << 16) | (g << 8) | b;
            }
        }
    } else if (format == X_ImageFormat_ZPixmap && depth == 32) {
        /* 32-bit ZPixmap: 4 bytes per pixel (BGRA or ARGB) */
        for (int py = 0; py < clip_h; py++) {
            const unsigned char *row = data + py * src_stride;
            for (int px = 0; px < clip_w; px++) {
                int idx = px * 4;
                if (idx + 3 >= src_stride) break;
                uint8_t b = row[idx];
                uint8_t g = row[idx + 1];
                uint8_t r = row[idx + 2];
                uint8_t a = row[idx + 3];
                fb[(dst_y + py) * server->width + (dst_x + px)] =
                    (a << 24) | (r << 16) | (g << 8) | b;
            }
        }
    } else if (format == X_ImageFormat_ZPixmap && depth == 16) {
        /* 16-bit ZPixmap: 2 bytes per pixel (RGB565) */
        for (int py = 0; py < clip_h; py++) {
            const unsigned char *row = data + py * src_stride;
            for (int px = 0; px < clip_w; px++) {
                int idx = px * 2;
                uint16_t pixel = row[idx] | (row[idx + 1] << 8);
                uint8_t r = ((pixel >> 11) & 0x1F) << 3;
                uint8_t g = ((pixel >> 5) & 0x3F) << 2;
                uint8_t b = (pixel & 0x1F) << 3;
                fb[(dst_y + py) * server->width + (dst_x + px)] = 0xFF000000 | (r << 16) | (g << 8) | b;
            }
        }
    }
    mark_dirty(server, dst_x, dst_y, clip_w, clip_h);
}

/* Copy area within framebuffer */
static void fb_copy_area(X11Server *server, int src_x, int src_y,
                         int dst_x, int dst_y, int w, int h) {
    /* Use a temporary buffer to handle overlapping regions */
    uint32_t *tmp = (uint32_t *)malloc(w * h * 4);
    if (!tmp) return;

    uint32_t *fb = (uint32_t *)server->framebuffer;
    for (int py = 0; py < h; py++) {
        for (int px = 0; px < w; px++) {
            int sx = src_x + px, sy = src_y + py;
            if (sx >= 0 && sx < server->width && sy >= 0 && sy < server->height) {
                tmp[py * w + px] = fb[sy * server->width + sx];
            } else {
                tmp[py * w + px] = 0;
            }
        }
    }
    for (int py = 0; py < h; py++) {
        for (int px = 0; px < w; px++) {
            int dx = dst_x + px, dy = dst_y + py;
            if (dx >= 0 && dx < server->width && dy >= 0 && dy < server->height) {
                fb[dy * server->width + dx] = tmp[py * w + px];
            }
        }
    }
    free(tmp);
    mark_dirty(server, dst_x, dst_y, w, h);
}

// ---- X11 Send Error ----

static void send_error(XClient *client, uint8_t code, uint16_t seq, uint32_t bad_value, uint16_t minor, uint8_t major) {
    unsigned char err[32];
    memset(err, 0, 32);
    err[0] = 0;  /* error indicator */
    err[1] = code;
    write_card16(err + 2, seq, client->byte_order);
    write_card32(err + 4, bad_value, client->byte_order);
    write_card16(err + 8, minor, client->byte_order);
    err[10] = major;
    write_exact(client->fd, err, 32);
}

// ---- X11 Send Event ----

static void send_event(XClient *client, const unsigned char *event) {
    write_exact(client->fd, event, 32);
}

/* Send an Expose event for a window */
static void send_expose_event(X11Server *server, int window_id, int x, int y, int w, int h) {
    for (int i = 0; i < MAX_CLIENTS; i++) {
        XClient *c = &server->clients[i];
        if (!c->connected) continue;
        unsigned char evt[32];
        memset(evt, 0, 32);
        evt[0] = X_Event_Expose;
        write_card32(evt + 4, window_id, c->byte_order);
        write_card16(evt + 8, x, c->byte_order);
        write_card16(evt + 10, y, c->byte_order);
        write_card16(evt + 12, w, c->byte_order);
        write_card16(evt + 14, h, c->byte_order);
        write_card16(evt + 16, 0, c->byte_order);  /* count */
        send_event(c, evt);
    }
}

/* Send a ConfigureNotify event for a window */
static void send_configure_notify(X11Server *server, int window_id) {
    XWindow *win = find_window(server, window_id);
    if (!win) return;
    for (int i = 0; i < MAX_CLIENTS; i++) {
        XClient *c = &server->clients[i];
        if (!c->connected) continue;
        unsigned char evt[32];
        memset(evt, 0, 32);
        evt[0] = X_Event_ConfigureNotify;
        write_card32(evt + 4, window_id, c->byte_order);
        write_card32(evt + 8, window_id, c->byte_order);  /* above */
        write_card16(evt + 12, win->x, c->byte_order);
        write_card16(evt + 14, win->y, c->byte_order);
        write_card16(evt + 16, win->w, c->byte_order);
        write_card16(evt + 18, win->h, c->byte_order);
        write_card16(evt + 20, 0, c->byte_order);  /* border_width */
        evt[22] = win->override_redirect ? 1 : 0;
        send_event(c, evt);
    }
}

// ---- X11 Client Handler ----

static void *client_handler(void *arg) {
    XClient *client = (XClient *)arg;
    X11Server *server = client->server;
    unsigned char *read_buf = (unsigned char *)malloc(READ_BUF_SIZE);
    if (!read_buf) {
        client->connected = 0;
        return NULL;
    }

    LOGI("Client #%d connected to display :%d", client->id, server->display_num);

    /* ---- X11 Connection Setup ---- */
    unsigned char setup[12];
    if (read_exact(client->fd, setup, 12) < 0) {
        LOGW("Client #%d: failed to read setup", client->id);
        goto disconnect;
    }

    client->byte_order = setup[0];  /* 0=LSB ('B'), 1=MSB ('l') — actually 0x42='B'=big, 0x6c='l'=little */
    if (setup[0] == 0x6c) client->byte_order = 0;  /* 'l' = little-endian */
    else client->byte_order = 1;  /* 'B' = big-endian */

    uint16_t proto_major = read_card16(client, setup + 2);
    uint16_t proto_minor = read_card16(client, setup + 4);
    uint16_t auth_name_len = read_card16(client, setup + 6);
    uint16_t auth_data_len = read_card16(client, setup + 8);

    /* Read auth data */
    int auth_pad = (4 - (auth_name_len % 4)) % 4;
    int auth_data_pad = (4 - (auth_data_len % 4)) % 4;
    if (auth_name_len > 0) {
        unsigned char *auth_name = (unsigned char *)malloc(auth_name_len + auth_pad);
        read_exact(client->fd, auth_name, auth_name_len + auth_pad);
        free(auth_name);
    }
    if (auth_data_len > 0) {
        unsigned char *auth_data = (unsigned char *)malloc(auth_data_len + auth_data_pad);
        read_exact(client->fd, auth_data, auth_data_len + auth_data_pad);
        free(auth_data);
    }

    LOGD("Client #%d: setup proto=%d.%d auth=%d+%d byte_order=%s",
         client->id, proto_major, proto_minor, auth_name_len, auth_data_len,
         client->byte_order == 0 ? "LSB" : "MSB");

    /* Send connection success response */
    unsigned char reply[40];
    memset(reply, 0, 40);
    reply[0] = 1;  /* success */
    reply[1] = 0;  /* padding */
    write_card16(reply + 2, X11_PROTOCOL_MAJOR, client->byte_order);
    write_card16(reply + 4, X11_PROTOCOL_MINOR, client->byte_order);
    write_card16(reply + 6, 8, client->byte_order);  /* additional data length in 4-byte units */
    write_card32(reply + 8, 0, client->byte_order);  /* release number */
    write_card32(reply + 12, server->root_window, client->byte_order);  /* resource base */
    write_card32(reply + 16, 0x001FFFFF, client->byte_order);  /* resource mask */
    write_card32(reply + 20, 256, client->byte_order);  /* motion buffer size */
    write_card16(reply + 24, 5, client->byte_order);  /* vendor length */
    write_card16(reply + 26, 1024, client->byte_order);  /* max request length */
    write_card16(reply + 28, 1, client->byte_order);  /* screens */
    write_card16(reply + 30, 1, client->byte_order);  /* formats */
    reply[32] = 0;  /* image byte order (0=LSB) */
    reply[33] = 0;  /* bitmap bit order (0=LSB) */
    write_card16(reply + 34, 32, client->byte_order);  /* scanline unit */
    write_card16(reply + 36, 32, client->byte_order);  /* scanline pad */
    write_card32(reply + 38, 0, client->byte_order);  /* min key code */
    /* Remaining bytes padded to 8*4=32 extra */

    write_exact(client->fd, reply, 40);

    /* Send screen info (8*4=32 bytes) */
    unsigned char screen[40];
    memset(screen, 0, 40);
    write_card32(screen + 0, server->root_window, client->byte_order);  /* root */
    write_card32(screen + 4, server->root_visual, client->byte_order);  /* default visual */
    /* Colormap */
    write_card32(screen + 8, server->root_window + 1, client->byte_order);  /* default colormap */
    write_card32(screen + 12, 0x00FFFFFF, client->byte_order);  /* white pixel */
    write_card32(screen + 16, 0, client->byte_order);  /* black pixel */
    write_card32(screen + 20, 0, client->byte_order);  /* current input masks */
    write_card16(screen + 24, server->width, client->byte_order);
    write_card16(screen + 26, server->height, client->byte_order);
    write_card16(screen + 28, 0, client->byte_order);  /* width in mm */
    write_card16(screen + 30, 0, client->byte_order);  /* height in mm */
    write_card16(screen + 32, 1, client->byte_order);  /* min installed maps */
    write_card16(screen + 34, 1, client->byte_order);  /* max installed maps */
    write_card32(screen + 36, server->root_visual, client->byte_order);  /* root visual */
    write_exact(client->fd, screen, 40);

    /* Send visual info (24 bytes) */
    unsigned char visual[24];
    memset(visual, 0, 24);
    write_card32(visual + 0, server->root_visual, client->byte_order);  /* visual id */
    visual[4] = 1;  /* class = TrueColor */
    visual[5] = 8;  /* bits per rgb */
    write_card16(visual + 6, 1, client->byte_order);  /* colormap entries */
    write_card32(visual + 8, 0x00FF0000, client->byte_order);  /* red mask */
    write_card32(visual + 12, 0x0000FF00, client->byte_order);  /* green mask */
    write_card32(visual + 16, 0x000000FF, client->byte_order);  /* blue mask */
    write_card32(visual + 20, 0, client->byte_order);  /* misc */
    write_exact(client->fd, visual, 24);

    /* Send vendor string "TermX" padded to 8 bytes */
    unsigned char vendor[8];
    memset(vendor, 0, 8);
    memcpy(vendor, "TermX", 5);
    write_exact(client->fd, vendor, 8);

    /* Send format list (8 bytes * 1 format) */
    unsigned char fmt[8];
    memset(fmt, 0, 8);
    fmt[0] = 32;  /* depth */
    fmt[1] = 32;  /* bits per pixel */
    fmt[2] = 4;   /* scanline pad (must be multiple of 8) */
    write_exact(client->fd, fmt, 8);

    /* Pad to make total additional data = 8 * 4 = 32 words as declared */
    /* Total additional data sent: 40+40+24+8+8 = 120 bytes = 30 words. Need 32-30=2 more words = 8 bytes */
    unsigned char pad[8];
    memset(pad, 0, 8);
    write_exact(client->fd, pad, 8);

    LOGI("Client #%d: setup complete, entering protocol loop", client->id);

    /* ---- Protocol Loop ---- */
    while (server->running && client->connected) {
        /* Read request header (4 bytes) */
        unsigned char hdr[4];
        if (read_exact(client->fd, hdr, 4) < 0) break;

        uint8_t opcode = hdr[0];
        uint8_t req_data = hdr[1];
        uint16_t req_len = read_card16(client, hdr + 2);  /* in 4-byte units */

        client->sequence++;

        /* Calculate total request bytes (excluding the 4-byte header) */
        int data_len = (req_len * 4) - 4;
        if (data_len < 0) data_len = 0;

        /* Read remaining request data */
        unsigned char *req_data_buf = NULL;
        if (data_len > 0) {
            if (data_len > READ_BUF_SIZE) {
                LOGW("Client #%d: request too large (%d bytes), opcode=%d",
                     client->id, data_len, opcode);
                /* Skip the data */
                unsigned char skip[4096];
                int remaining = data_len;
                while (remaining > 0) {
                    int to_read = remaining > 4096 ? 4096 : remaining;
                    if (read_exact(client->fd, skip, to_read) < 0) goto disconnect;
                    remaining -= to_read;
                }
                send_error(client, X_Error_Length, client->sequence, data_len, 0, opcode);
                continue;
            }
            req_data_buf = read_buf;
            if (read_exact(client->fd, req_data_buf, data_len) < 0) break;
        }

        pthread_mutex_lock(&server->lock);

        /* Process the request */
        switch (opcode) {
            case X_CreateWindow: {
                uint32_t wid = read_card32(client, req_data_buf);
                int x = (int16_t)read_card16(client, req_data_buf + 8);
                int y = (int16_t)read_card16(client, req_data_buf + 10);
                int w = read_card16(client, req_data_buf + 12);
                int h = read_card16(client, req_data_buf + 14);
                int xclass = req_data_buf[20];  /* 1=InputOutput, 2=InputOnly */
                int depth = req_data_buf[7];
                int override = 0;

                /* Parse value list if present */
                uint32_t valuemask = read_card32(client, req_data_buf + 24);
                int val_offset = 28;
                if (valuemask & 0x0002) { /* CWBackPixel */
                    /* background pixel at val_offset */
                    val_offset += 4;
                }
                if (valuemask & 0x0400) { /* CWOverrideRedirect */
                    override = read_card32(client, req_data_buf + val_offset) ? 1 : 0;
                    val_offset += 4;
                }

                if (server->window_count < MAX_WINDOWS) {
                    XWindow *win = &server->windows[server->window_count];
                    win->x = x; win->y = y; win->w = w; win->h = h;
                    win->mapped = 0;
                    win->override_redirect = override;
                    win->bg_pixel = 0xFF1E1E2E;
                    win->event_mask = 0;
                    win->xclass = xclass;
                    win->depth = depth;
                    win->parent = (int)read_card32(client, req_data_buf + 4);
                    server->window_count++;
                }
                LOGD("Client #%d: CreateWindow id=%u %dx%d at (%d,%d)",
                     client->id, wid, w, h, x, y);
                break;
            }

            case X_MapWindow: {
                uint32_t wid = read_card32(client, req_data_buf);
                XWindow *win = find_window(server, wid);
                if (win) {
                    win->mapped = 1;
                    send_expose_event(server, wid, 0, 0, win->w, win->h);
                    /* Send MapNotify */
                    for (int i = 0; i < MAX_CLIENTS; i++) {
                        XClient *c = &server->clients[i];
                        if (!c->connected) continue;
                        unsigned char evt[32];
                        memset(evt, 0, 32);
                        evt[0] = X_Event_MapNotify;
                        write_card32(evt + 4, wid, c->byte_order);
                        write_card32(evt + 8, wid, c->byte_order);
                        evt[12] = 0;  /* override */
                        send_event(c, evt);
                    }
                }
                LOGD("Client #%d: MapWindow id=%u", client->id, wid);
                break;
            }

            case X_UnmapWindow: {
                uint32_t wid = read_card32(client, req_data_buf);
                XWindow *win = find_window(server, wid);
                if (win) win->mapped = 0;
                LOGD("Client #%d: UnmapWindow id=%u", client->id, wid);
                break;
            }

            case X_DestroyWindow: {
                uint32_t wid = read_card32(client, req_data_buf);
                LOGD("Client #%d: DestroyWindow id=%u", client->id, wid);
                break;
            }

            case X_ConfigureWindow: {
                uint32_t wid = read_card32(client, req_data_buf);
                uint16_t mask = read_card16(client, req_data_buf + 2);
                XWindow *win = find_window(server, wid);
                int off = 4;
                if (win) {
                    if (mask & 0x0001) { win->x = (int16_t)read_card16(client, req_data_buf + off); off += 4; }
                    if (mask & 0x0002) { win->y = (int16_t)read_card16(client, req_data_buf + off); off += 4; }
                    if (mask & 0x0004) { win->w = read_card16(client, req_data_buf + off); off += 4; }
                    if (mask & 0x0008) { win->h = read_card16(client, req_data_buf + off); off += 4; }
                    send_configure_notify(server, wid);
                }
                break;
            }

            case X_CreateGC: {
                if (server->gc_count < MAX_GC) {
                    XGC *gc = &server->gcs[server->gc_count];
                    memset(gc, 0, sizeof(XGC));
                    gc->id = read_card32(client, req_data_buf);
                    gc->function = 3;  /* GXcopy */
                    gc->plane_mask = 0xFFFFFFFF;
                    gc->fg_pixel = 0;
                    gc->bg_pixel = 0xFFFFFFFF;
                    gc->fill_style = 0;  /* Solid */
                    gc->graphics_exposures = 1;
                    gc->clip_x2 = server->width;
                    gc->clip_y2 = server->height;

                    /* Parse value mask and values */
                    uint32_t vmask = read_card32(client, req_data_buf + 8);
                    int voff = 12;
                    if (vmask & 0x000001) { gc->function = read_card32(client, req_data_buf + voff); voff += 4; }
                    if (vmask & 0x000002) { gc->plane_mask = read_card32(client, req_data_buf + voff); voff += 4; }
                    if (vmask & 0x000004) { gc->fg_pixel = read_card32(client, req_data_buf + voff); voff += 4; }
                    if (vmask & 0x000008) { gc->bg_pixel = read_card32(client, req_data_buf + voff); voff += 4; }
                    if (vmask & 0x000040) { gc->fill_style = read_card32(client, req_data_buf + voff); voff += 4; }
                    if (vmask & 0x08000) { gc->graphics_exposures = read_card32(client, req_data_buf + voff); voff += 4; }
                    if (vmask & 0x04000) { gc->clip_x_origin = read_card32(client, req_data_buf + voff); voff += 4; }
                    if (vmask & 0x08000) { gc->clip_y_origin = read_card32(client, req_data_buf + voff); voff += 4; }

                    server->gc_count++;
                }
                LOGD("Client #%d: CreateGC", client->id);
                break;
            }

            case X_ChangeGC: {
                uint32_t gcid = read_card32(client, req_data_buf);
                XGC *gc = find_gc(server, gcid);
                if (gc) {
                    uint32_t vmask = read_card32(client, req_data_buf + 4);
                    int voff = 8;
                    if (vmask & 0x000001) { gc->function = read_card32(client, req_data_buf + voff); voff += 4; }
                    if (vmask & 0x000002) { gc->plane_mask = read_card32(client, req_data_buf + voff); voff += 4; }
                    if (vmask & 0x000004) { gc->fg_pixel = read_card32(client, req_data_buf + voff); voff += 4; }
                    if (vmask & 0x000008) { gc->bg_pixel = read_card32(client, req_data_buf + voff); voff += 4; }
                    if (vmask & 0x000040) { gc->fill_style = read_card32(client, req_data_buf + voff); voff += 4; }
                    if (vmask & 0x08000) { gc->graphics_exposures = read_card32(client, req_data_buf + voff); voff += 4; }
                }
                break;
            }

            case X_FreeGC: {
                uint32_t gcid = read_card32(client, req_data_buf);
                for (int i = 0; i < server->gc_count; i++) {
                    if (server->gcs[i].id == (int)gcid) {
                        server->gcs[i] = server->gcs[server->gc_count - 1];
                        server->gc_count--;
                        break;
                    }
                }
                break;
            }

            case X_CreatePixmap: {
                if (server->pixmap_count < MAX_PIXMAPS) {
                    XPixmap *pm = &server->pixmaps[server->pixmap_count];
                    pm->id = read_card32(client, req_data_buf);
                    pm->depth = req_data_buf[4];
                    pm->width = read_card16(client, req_data_buf + 8);
                    pm->height = read_card16(client, req_data_buf + 10);
                    pm->data = (unsigned char *)calloc(pm->width * pm->height, 4);
                    server->pixmap_count++;
                }
                break;
            }

            case X_FreePixmap: {
                uint32_t pmid = read_card32(client, req_data_buf);
                for (int i = 0; i < server->pixmap_count; i++) {
                    if (server->pixmaps[i].id == (int)pmid) {
                        free(server->pixmaps[i].data);
                        server->pixmaps[i] = server->pixmaps[server->pixmap_count - 1];
                        server->pixmap_count--;
                        break;
                    }
                }
                break;
            }

            case X_PutImage: {
                uint8_t format = req_data_buf[0];
                uint32_t drawable = read_card32(client, req_data_buf);
                uint32_t gcid = read_card32(client, req_data_buf + 4);
                uint16_t total_w = read_card16(client, req_data_buf + 8);
                uint16_t total_h = read_card16(client, req_data_buf + 10);
                int16_t dst_x = read_card16(client, req_data_buf + 12);
                int16_t dst_y = read_card16(client, req_data_buf + 14);
                uint8_t depth = req_data_buf[16];
                uint8_t left_pad = req_data_buf[17];

                /* Image data starts at offset 20 in request (after 4-byte header + 20 bytes) */
                int bpp = (depth > 16) ? 4 : (depth > 8) ? 3 : 1;
                int src_stride = total_w * bpp;
                int image_data_len = data_len - 16;  /* subtract the PutImage header */

                if (image_data_len > 0 && req_data_buf + 16 < read_buf + READ_BUF_SIZE) {
                    fb_put_image(server, dst_x, dst_y, total_w, total_h,
                                 req_data_buf + 16, src_stride, format, depth, client->byte_order);
                }
                LOGD("Client #%d: PutImage %dx%d at (%d,%d) fmt=%d depth=%d",
                     client->id, total_w, total_h, dst_x, dst_y, format, depth);
                break;
            }

            case X_GetImage: {
                uint32_t drawable = read_card32(client, req_data_buf);
                int16_t x = read_card16(client, req_data_buf + 4);
                int16_t y = read_card16(client, req_data_buf + 6);
                uint16_t w = read_card16(client, req_data_buf + 8);
                uint16_t h = read_card16(client, req_data_buf + 10);
                uint32_t plane_mask = read_card32(client, req_data_buf + 12);
                uint8_t format = req_data_buf[1];

                /* Reply with image data */
                int img_size = w * h * 4;
                int reply_len = 32 + ((img_size + 3) & ~3);
                unsigned char *img_reply = (unsigned char *)calloc(reply_len, 1);
                if (img_reply) {
                    img_reply[0] = 1;  /* reply */
                    img_reply[1] = 32;  /* depth */
                    write_card32(img_reply + 4, 0, client->byte_order);  /* visual */
                    write_card32(img_reply + 8, (img_size + 3) / 4, client->byte_order);  /* length */

                    /* Copy framebuffer data */
                    uint32_t *fb = (uint32_t *)server->framebuffer;
                    uint32_t *out = (uint32_t *)(img_reply + 32);
                    for (int py = 0; py < h; py++) {
                        for (int px = 0; px < w; px++) {
                            int fx = x + px, fy = y + py;
                            if (fx >= 0 && fx < server->width && fy >= 0 && fy < server->height) {
                                out[py * w + px] = fb[fy * server->width + fx];
                            } else {
                                out[py * w + px] = 0;
                            }
                        }
                    }
                    write_exact(client->fd, img_reply, reply_len);
                    free(img_reply);
                }
                break;
            }

            case X_CopyArea: {
                uint32_t src = read_card32(client, req_data_buf);
                uint32_t dst = read_card32(client, req_data_buf + 4);
                uint32_t gcid = read_card32(client, req_data_buf + 8);
                int16_t src_x = read_card16(client, req_data_buf + 12);
                int16_t src_y = read_card16(client, req_data_buf + 14);
                int16_t dst_x = read_card16(client, req_data_buf + 16);
                int16_t dst_y = read_card16(client, req_data_buf + 18);
                uint16_t w = read_card16(client, req_data_buf + 20);
                uint16_t h = read_card16(client, req_data_buf + 22);

                fb_copy_area(server, src_x, src_y, dst_x, dst_y, w, h);
                LOGD("Client #%d: CopyArea %dx%d from (%d,%d) to (%d,%d)",
                     client->id, w, h, src_x, src_y, dst_x, dst_y);
                break;
            }

            case X_PolyFillRectangle: {
                uint32_t drawable = read_card32(client, req_data_buf);
                uint32_t gcid = read_card32(client, req_data_buf + 4);
                XGC *gc = find_gc(server, gcid);
                uint32_t color = gc ? gc->fg_pixel : 0;

                for (int i = 8; i + 7 < data_len; i += 8) {
                    int16_t rx = read_card16(client, req_data_buf + i);
                    int16_t ry = read_card16(client, req_data_buf + i + 2);
                    uint16_t rw = read_card16(client, req_data_buf + i + 4);
                    uint16_t rh = read_card16(client, req_data_buf + i + 6);
                    fb_fill_rect(server, rx, ry, rw, rh, color);
                }
                break;
            }

            case X_PolyRectangle: {
                /* Draw rectangle outlines */
                uint32_t drawable = read_card32(client, req_data_buf);
                uint32_t gcid = read_card32(client, req_data_buf + 4);
                XGC *gc = find_gc(server, gcid);
                uint32_t color = gc ? gc->fg_pixel : 0;

                for (int i = 8; i + 7 < data_len; i += 8) {
                    int16_t rx = read_card16(client, req_data_buf + i);
                    int16_t ry = read_card16(client, req_data_buf + i + 2);
                    uint16_t rw = read_card16(client, req_data_buf + i + 4);
                    uint16_t rh = read_card16(client, req_data_buf + i + 6);
                    /* Draw outline: top, bottom, left, right */
                    for (int px = rx; px < rx + rw; px++) {
                        fb_put_pixel(server, px, ry, color);
                        fb_put_pixel(server, px, ry + rh - 1, color);
                    }
                    for (int py = ry; py < ry + rh; py++) {
                        fb_put_pixel(server, rx, py, color);
                        fb_put_pixel(server, rx + rw - 1, py, color);
                    }
                    mark_dirty(server, rx, ry, rw, rh);
                }
                break;
            }

            case X_PolyLine: {
                uint8_t coord_mode = req_data_buf[1];
                uint32_t drawable = read_card32(client, req_data_buf);
                uint32_t gcid = read_card32(client, req_data_buf + 4);
                XGC *gc = find_gc(server, gcid);
                uint32_t color = gc ? gc->fg_pixel : 0;

                int prev_x = 0, prev_y = 0;
                for (int i = 8; i + 3 < data_len; i += 4) {
                    int16_t px = read_card16(client, req_data_buf + i);
                    int16_t py = read_card16(client, req_data_buf + i + 2);
                    if (i > 8) {
                        /* Simple Bresenham line from (prev_x, prev_y) to (px, py) */
                        int dx = abs(px - prev_x), dy = abs(py - prev_y);
                        int sx = prev_x < px ? 1 : -1, sy = prev_y < py ? 1 : -1;
                        int err = dx - dy, cx = prev_x, cy = prev_y;
                        while (1) {
                            fb_put_pixel(server, cx, cy, color);
                            if (cx == px && cy == py) break;
                            int e2 = 2 * err;
                            if (e2 > -dy) { err -= dy; cx += sx; }
                            if (e2 < dx) { err += dx; cy += sy; }
                        }
                    }
                    prev_x = px; prev_y = py;
                }
                break;
            }

            case X_PolyPoint: {
                uint8_t coord_mode = req_data_buf[1];
                uint32_t drawable = read_card32(client, req_data_buf);
                uint32_t gcid = read_card32(client, req_data_buf + 4);
                XGC *gc = find_gc(server, gcid);
                uint32_t color = gc ? gc->fg_pixel : 0;

                for (int i = 8; i + 3 < data_len; i += 4) {
                    int16_t px = read_card16(client, req_data_buf + i);
                    int16_t py = read_card16(client, req_data_buf + i + 2);
                    fb_put_pixel(server, px, py, color);
                }
                break;
            }

            case X_InternAtom: {
                uint8_t only_if_exists = req_data_buf[0];
                uint16_t name_len = read_card16(client, req_data_buf + 2);
                char name[256] = {0};
                if (name_len > 0 && name_len < 256) {
                    memcpy(name, req_data_buf + 8, name_len);
                    name[name_len] = 0;
                }
                int atom = intern_atom(server, name, only_if_exists);

                unsigned char reply_buf[32];
                memset(reply_buf, 0, 32);
                reply_buf[0] = 1;  /* reply */
                write_card32(reply_buf + 8, atom, client->byte_order);
                write_exact(client->fd, reply_buf, 32);
                break;
            }

            case X_GetAtomName: {
                uint32_t atom = read_card32(client, req_data_buf);
                const char *name = "UNKNOWN";
                for (int i = 0; i < server->atom_count; i++) {
                    if (server->atoms[i].atom == (int)atom) {
                        name = server->atoms[i].name;
                        break;
                    }
                }
                int name_len = strlen(name);
                int padded = (name_len + 3) & ~3;
                int reply_len = 32 + padded;
                unsigned char *atom_reply = (unsigned char *)calloc(reply_len, 1);
                if (atom_reply) {
                    atom_reply[0] = 1;
                    write_card16(atom_reply + 8, name_len, client->byte_order);
                    memcpy(atom_reply + 32, name, name_len);
                    write_exact(client->fd, atom_reply, reply_len);
                    free(atom_reply);
                }
                break;
            }

            case X_ChangeProperty: {
                uint8_t mode = req_data_buf[0];  /* 0=Replace, 1=Prepend, 2=Append */
                uint32_t window = read_card32(client, req_data_buf);
                uint32_t property = read_card32(client, req_data_buf + 4);
                uint32_t type = read_card32(client, req_data_buf + 8);
                uint8_t format = req_data_buf[12];
                uint32_t num_units = read_card32(client, req_data_buf + 16);

                if (server->property_count < MAX_PROPERTIES) {
                    XProperty *prop = &server->properties[server->property_count];
                    prop->atom = property;
                    prop->window = window;
                    prop->type = type;
                    prop->format = format;
                    prop->data_len = num_units * (format / 8);
                    if (prop->data_len > 4096) prop->data_len = 4096;
                    memcpy(prop->data, req_data_buf + 20, prop->data_len);
                    server->property_count++;

                    /* Handle WM_NAME specially */
                    if (property == XA_WM_NAME || property == intern_atom(server, "_NET_WM_NAME", 1)) {
                        LOGD("Client #%d: Window %u name set", client->id, window);
                    }
                }
                break;
            }

            case X_GetProperty: {
                uint8_t delete = req_data_buf[0];
                uint32_t window = read_card32(client, req_data_buf);
                uint32_t property = read_card32(client, req_data_buf + 4);
                uint32_t type = read_card32(client, req_data_buf + 8);
                uint32_t long_offset = read_card32(client, req_data_buf + 12);
                uint32_t long_length = read_card32(client, req_data_buf + 16);

                /* Search for the property */
                XProperty *found = NULL;
                for (int i = 0; i < server->property_count; i++) {
                    if (server->properties[i].atom == (int)property &&
                        server->properties[i].window == (int)window) {
                        found = &server->properties[i];
                        break;
                    }
                }

                int reply_len;
                if (found) {
                    int data_bytes = found->data_len;
                    int padded = (data_bytes + 3) & ~3;
                    reply_len = 32 + padded;
                    unsigned char *gp_reply = (unsigned char *)calloc(reply_len, 1);
                    if (gp_reply) {
                        gp_reply[0] = 1;
                        write_card32(gp_reply + 8, found->type, client->byte_order);
                        gp_reply[12] = found->format;
                        write_card32(gp_reply + 16, found->data_len / (found->format / 8), client->byte_order);
                        write_card32(gp_reply + 20, (data_bytes + 3) / 4, client->byte_order);
                        memcpy(gp_reply + 32, found->data, data_bytes);
                        write_exact(client->fd, gp_reply, reply_len);
                        free(gp_reply);
                    }
                } else {
                    /* Property not found - return None */
                    unsigned char gp_reply[32];
                    memset(gp_reply, 0, 32);
                    gp_reply[0] = 1;
                    write_card32(gp_reply + 8, 0, client->byte_order);  /* type = None */
                    write_exact(client->fd, gp_reply, 32);
                }
                break;
            }

            case X_QueryExtension: {
                uint16_t name_len = read_card16(client, req_data_buf + 2);
                char ext_name[256] = {0};
                if (name_len > 0 && name_len < 256) {
                    memcpy(ext_name, req_data_buf + 8, name_len);
                    ext_name[name_len] = 0;
                }

                unsigned char ext_reply[32];
                memset(ext_reply, 0, 32);
                ext_reply[0] = 1;

                if (strcmp(ext_name, "BIG-REQUESTS") == 0) {
                    ext_reply[8] = 1;  /* present */
                    write_card16(ext_reply + 10, 1, client->byte_order);  /* major */
                    write_card16(ext_reply + 12, 0, client->byte_order);  /* minor */
                } else if (strcmp(ext_name, "MIT-SHM") == 0) {
                    ext_reply[8] = 1;
                    write_card16(ext_reply + 10, 1, client->byte_order);
                } else if (strcmp(ext_name, "RANDR") == 0) {
                    ext_reply[8] = 1;
                    write_card16(ext_reply + 10, 1, client->byte_order);
                } else if (strcmp(ext_name, "RENDER") == 0) {
                    ext_reply[8] = 1;
                    write_card16(ext_reply + 10, 0, client->byte_order);
                }
                write_exact(client->fd, ext_reply, 32);
                LOGD("Client #%d: QueryExtension '%s' -> %s",
                     client->id, ext_name, ext_reply[8] ? "yes" : "no");
                break;
            }

            case X_ListExtensions: {
                const char *extensions[] = {"BIG-REQUESTS", "MIT-SHM", "RANDR", NULL};
                int total_len = 0;
                for (int i = 0; extensions[i]; i++) {
                    total_len += strlen(extensions[i]) + 1;
                }
                int padded = (total_len + 3) & ~3;
                int reply_len = 32 + padded;
                unsigned char *le_reply = (unsigned char *)calloc(reply_len, 1);
                if (le_reply) {
                    le_reply[0] = 1;
                    le_reply[1] = 3;  /* number of extensions */
                    write_card32(le_reply + 8, (padded + 3) / 4, client->byte_order);
                    int off = 32;
                    for (int i = 0; extensions[i]; i++) {
                        int len = strlen(extensions[i]);
                        le_reply[off++] = len;
                        memcpy(le_reply + off, extensions[i], len);
                        off += len;
                    }
                    write_exact(client->fd, le_reply, reply_len);
                    free(le_reply);
                }
                break;
            }

            case X_GetInputFocus: {
                unsigned char reply_buf[32];
                memset(reply_buf, 0, 32);
                reply_buf[0] = 1;
                reply_buf[1] = 1;  /* FocusParent */
                write_card32(reply_buf + 8, server->root_window, client->byte_order);  /* focus window */
                write_exact(client->fd, reply_buf, 32);
                break;
            }

            case X_SetInputFocus: {
                /* Just acknowledge, no real action needed */
                break;
            }

            case X_QueryPointer: {
                unsigned char reply_buf[32];
                memset(reply_buf, 0, 32);
                reply_buf[0] = 1;
                reply_buf[1] = 1;  /* same screen */
                write_card32(reply_buf + 8, server->root_window, client->byte_order);
                write_card32(reply_buf + 12, 0, client->byte_order);  /* child */
                write_card16(reply_buf + 16, server->mouse_x, client->byte_order);
                write_card16(reply_buf + 18, server->mouse_y, client->byte_order);
                write_card16(reply_buf + 20, server->mouse_x, client->byte_order);
                write_card16(reply_buf + 22, server->mouse_y, client->byte_order);
                write_card32(reply_buf + 24, server->mouse_buttons, client->byte_order);
                write_exact(client->fd, reply_buf, 32);
                break;
            }

            case X_GetGeometry: {
                uint32_t drawable = read_card32(client, req_data_buf);
                unsigned char reply_buf[32];
                memset(reply_buf, 0, 32);
                reply_buf[0] = 1;
                write_card32(reply_buf + 8, server->root_window, client->byte_order);  /* root */
                write_card16(reply_buf + 12, 0, client->byte_order);  /* depth = 32 */
                reply_buf[12] = 32;
                write_card16(reply_buf + 14, 0, client->byte_order);  /* x */
                write_card16(reply_buf + 16, 0, client->byte_order);  /* y */
                write_card16(reply_buf + 18, server->width, client->byte_order);
                write_card16(reply_buf + 20, server->height, client->byte_order);
                write_card16(reply_buf + 22, 0, client->byte_order);  /* border width */
                write_exact(client->fd, reply_buf, 32);
                break;
            }

            case X_OpenFont: {
                /* Acknowledge font open - we use a minimal stub */
                break;
            }

            case X_CloseFont: {
                break;
            }

            case X_QueryFont: {
                /* Return minimal font info */
                int reply_len = 60 + 8;  /* font info + char info for 1 char */
                unsigned char *qf_reply = (unsigned char *)calloc(reply_len, 1);
                if (qf_reply) {
                    qf_reply[0] = 1;
                    write_card32(qf_reply + 4, 0, client->byte_order);
                    write_card32(qf_reply + 8, (reply_len - 32 + 3) / 4, client->byte_order);
                    /* Min bounds: 4*2 + 2*4 + 6 = 22 bytes */
                    write_card16(qf_reply + 40, 1, client->byte_order);  /* min_byte1 */
                    write_card16(qf_reply + 42, 0xFF, client->byte_order);  /* max_byte1 */
                    write_card16(qf_reply + 44, 1, client->byte_order);  /* min_char_or_byte2 */
                    write_card16(qf_reply + 46, 0xFF, client->byte_order);  /* max_char_or_byte2 */
                    write_card16(qf_reply + 48, 0, client->byte_order);  /* default_char */
                    write_card16(qf_reply + 50, 0, client->byte_order);  /* draw_direction */
                    write_card16(qf_reply + 52, 1, client->byte_order);  /* min_byte1 */
                    write_card32(qf_reply + 56, 0, client->byte_order);  /* all_chars_exist */
                    write_card32(qf_reply + 60, 8, client->byte_order);  /* font_ascent */
                    write_card32(qf_reply + 64, 2, client->byte_order);  /* font_descent */
                    write_card32(qf_reply + 68, 0, client->byte_order);  /* n_char_infos */
                    write_exact(client->fd, qf_reply, reply_len);
                    free(qf_reply);
                }
                break;
            }

            case X_ListFonts: {
                uint16_t max_names = read_card16(client, req_data_buf + 2);
                uint16_t pattern_len = read_card16(client, req_data_buf + 4);
                /* Return a minimal font list */
                const char *fonts[] = {"fixed", "cursor", "-misc-fixed-*", NULL};
                int total_len = 0;
                for (int i = 0; fonts[i]; i++) {
                    total_len += strlen(fonts[i]) + 1;
                }
                int padded = (total_len + 3) & ~3;
                int reply_len = 32 + padded;
                unsigned char *lf_reply = (unsigned char *)calloc(reply_len, 1);
                if (lf_reply) {
                    lf_reply[0] = 1;
                    write_card16(lf_reply + 8, 3, client->byte_order);  /* number of names */
                    write_card32(lf_reply + 12, (padded + 3) / 4, client->byte_order);
                    int off = 32;
                    for (int i = 0; fonts[i]; i++) {
                        int len = strlen(fonts[i]);
                        lf_reply[off++] = len;
                        memcpy(lf_reply + off, fonts[i], len);
                        off += len;
                    }
                    write_exact(client->fd, lf_reply, reply_len);
                    free(lf_reply);
                }
                break;
            }

            case X_ImageText8: {
                uint8_t str_len = req_data_buf[0];
                uint32_t drawable = read_card32(client, req_data_buf);
                uint32_t gcid = read_card32(client, req_data_buf + 4);
                int16_t x = read_card16(client, req_data_buf + 8);
                int16_t y = read_card16(client, req_data_buf + 10);
                /* We don't actually render text, just log it */
                char text[256] = {0};
                if (str_len > 0 && str_len < 256) {
                    memcpy(text, req_data_buf + 16, str_len);
                    text[str_len] = 0;
                }
                LOGD("Client #%d: ImageText8 '%s' at (%d,%d)", client->id, text, x, y);
                break;
            }

            case X_ClearArea: {
                uint8_t exposures = req_data_buf[0];
                uint32_t window = read_card32(client, req_data_buf);
                int16_t x = read_card16(client, req_data_buf + 4);
                int16_t y = read_card16(client, req_data_buf + 6);
                uint16_t w = read_card16(client, req_data_buf + 8);
                uint16_t h = read_card16(client, req_data_buf + 10);
                fb_fill_rect(server, x, y, w, h, 0xFF1E1E2E);
                if (exposures) {
                    send_expose_event(server, window, x, y, w, h);
                }
                break;
            }

            case X_GrabServer: {
                break;
            }

            case X_UngrabServer: {
                break;
            }

            case X_AllocColor: {
                uint16_t red = read_card16(client, req_data_buf);
                uint16_t green = read_card16(client, req_data_buf + 2);
                uint16_t blue = read_card16(client, req_data_buf + 4);

                unsigned char ac_reply[32];
                memset(ac_reply, 0, 32);
                ac_reply[0] = 1;
                write_card16(ac_reply + 8, red, client->byte_order);
                write_card16(ac_reply + 10, green, client->byte_order);
                write_card16(ac_reply + 12, blue, client->byte_order);
                write_card32(ac_reply + 16, 0xFF000000 | ((red >> 8) << 16) | ((green >> 8) << 8) | (blue >> 8),
                            client->byte_order);  /* pixel */
                write_exact(client->fd, ac_reply, 32);
                break;
            }

            case X_CreateColormap: {
                /* Just acknowledge */
                break;
            }

            case X_FreeColormap: {
                break;
            }

            case X_CreateCursor:
            case X_CreateGlyphCursor:
            case X_FreeCursor: {
                break;
            }

            case X_NoOperation: {
                break;
            }

            default: {
                LOGD("Client #%d: unhandled opcode %d (len=%d)", client->id, opcode, req_len);
                /* Send Implementation error for truly unknown opcodes */
                if (opcode > 127) {
                    send_error(client, X_Error_Implementation, client->sequence, 0, 0, opcode);
                }
                break;
            }
        }

        pthread_mutex_unlock(&server->lock);
    }

disconnect:
    client->connected = 0;
    if (client->fd >= 0) close(client->fd);
    client->fd = -1;
    server->client_count--;
    free(read_buf);
    LOGI("Client #%d disconnected from display :%d", client->id, server->display_num);
    return NULL;
}

// ---- Accept Thread ----

static void *accept_thread(void *arg) {
    X11Server *server = (X11Server *)arg;

    LOGI("X11 server accept thread started for display :%d on port %d",
         server->display_num, server->port);

    while (server->running) {
        struct sockaddr_in client_addr;
        socklen_t client_len = sizeof(client_addr);
        int client_fd = accept(server->server_fd, (struct sockaddr *)&client_addr, &client_len);

        if (client_fd < 0) {
            if (server->running) {
                LOGW("Accept failed: %s", strerror(errno));
            }
            continue;
        }

        pthread_mutex_lock(&server->lock);

        /* Find a free client slot */
        int slot = -1;
        for (int i = 0; i < MAX_CLIENTS; i++) {
            if (!server->clients[i].connected) {
                slot = i;
                break;
            }
        }

        if (slot < 0) {
            LOGW("Max clients reached, rejecting connection");
            close(client_fd);
            pthread_mutex_unlock(&server->lock);
            continue;
        }

        server->clients[slot].fd = client_fd;
        server->clients[slot].id = slot + 1;
        server->clients[slot].connected = 1;
        server->clients[slot].server = server;
        server->clients[slot].sequence = 0;
        server->client_count++;

        pthread_create(&server->clients[slot].thread, NULL, client_handler, &server->clients[slot]);
        pthread_detach(server->clients[slot].thread);

        pthread_mutex_unlock(&server->lock);
    }

    return NULL;
}

// ---- Server Start/Stop ----

static X11Server *create_server(int display_num, int width, int height) {
    X11Server *server = (X11Server *)calloc(1, sizeof(X11Server));
    if (!server) return NULL;

    server->display_num = display_num;
    server->width = (width > 0 && width <= MAX_WIDTH) ? width : 1024;
    server->height = (height > 0 && height <= MAX_HEIGHT) ? height : 768;
    server->port = 6000 + display_num;
    server->running = 0;
    server->server_fd = -1;

    /* Allocate framebuffer */
    server->fb_size = server->width * server->height * 4;
    server->framebuffer = (unsigned char *)calloc(1, server->fb_size);
    if (!server->framebuffer) {
        free(server);
        return NULL;
    }

    /* Initialize framebuffer to dark background */
    uint32_t *fb = (uint32_t *)server->framebuffer;
    for (int i = 0; i < server->width * server->height; i++) {
        fb[i] = 0xFF1E1E2E;  /* Catppuccin Mocha background */
    }
    server->has_dirty = 1;
    server->dirty_x = 0;
    server->dirty_y = 0;
    server->dirty_w = server->width;
    server->dirty_h = server->height;

    /* Initialize resources */
    server->root_window = alloc_resource_id(server);
    server->root_visual = alloc_resource_id(server);
    server->next_resource_id = 100;

    /* Create root window */
    server->windows[0].x = 0;
    server->windows[0].y = 0;
    server->windows[0].w = server->width;
    server->windows[0].h = server->height;
    server->windows[0].mapped = 1;
    server->windows[0].override_redirect = 0;
    server->windows[0].bg_pixel = 0xFF1E1E2E;
    server->windows[0].xclass = 1;  /* InputOutput */
    server->windows[0].depth = 32;
    server->windows[0].parent = 0;
    server->window_count = 1;

    /* Pre-register common atoms */
    intern_atom(server, "WM_PROTOCOLS", 0);
    intern_atom(server, "WM_DELETE_WINDOW", 0);
    intern_atom(server, "_NET_WM_NAME", 0);
    intern_atom(server, "_NET_WM_STATE", 0);
    intern_atom(server, "UTF8_STRING", 0);
    intern_atom(server, "COMPOUND_TEXT", 0);
    intern_atom(server, "_MOTIF_WM_HINTS", 0);
    intern_atom(server, "_NET_WM_ICON", 0);
    intern_atom(server, "_NET_WM_PID", 0);
    intern_atom(server, "WM_CLIENT_LEADER", 0);
    intern_atom(server, "_NET_WM_WINDOW_TYPE", 0);
    intern_atom(server, "_NET_WM_WINDOW_TYPE_NORMAL", 0);
    intern_atom(server, "_NET_WM_WINDOW_TYPE_DIALOG", 0);
    intern_atom(server, "_NET_SUPPORTED", 0);
    intern_atom(server, "_NET_SUPPORTING_WM_CHECK", 0);
    intern_atom(server, "XdndAware", 0);
    intern_atom(server, "_NET_WM_BYPASS_COMPOSITOR", 0);

    pthread_mutex_init(&server->lock, NULL);

    /* Initialize clients */
    for (int i = 0; i < MAX_CLIENTS; i++) {
        server->clients[i].connected = 0;
        server->clients[i].fd = -1;
    }

    return server;
}

static int start_server(X11Server *server) {
    if (server->running) return 1;

    /* Create TCP socket */
    server->server_fd = socket(AF_INET, SOCK_STREAM, 0);
    if (server->server_fd < 0) {
        LOGE("Failed to create socket: %s", strerror(errno));
        return 0;
    }

    /* Allow address reuse */
    int opt = 1;
    setsockopt(server->server_fd, SOL_SOCKET, SO_REUSEADDR, &opt, sizeof(opt));

    /* Bind */
    struct sockaddr_in addr;
    memset(&addr, 0, sizeof(addr));
    addr.sin_family = AF_INET;
    addr.sin_addr.s_addr = htonl(INADDR_LOOPBACK);  /* Only listen on localhost */
    addr.sin_port = htons(server->port);

    if (bind(server->server_fd, (struct sockaddr *)&addr, sizeof(addr)) < 0) {
        LOGE("Failed to bind port %d: %s", server->port, strerror(errno));
        close(server->server_fd);
        server->server_fd = -1;
        return 0;
    }

    /* Listen */
    if (listen(server->server_fd, 5) < 0) {
        LOGE("Failed to listen: %s", strerror(errno));
        close(server->server_fd);
        server->server_fd = -1;
        return 0;
    }

    server->running = 1;

    /* Start accept thread */
    pthread_create(&server->accept_thread, NULL, accept_thread, server);
    pthread_detach(server->accept_thread);

    LOGI("X11 server started: display :%d, %dx%d, port %d",
         server->display_num, server->width, server->height, server->port);

    return 1;
}

static void stop_server(X11Server *server) {
    if (!server) return;
    server->running = 0;

    /* Close server socket */
    if (server->server_fd >= 0) {
        close(server->server_fd);
        server->server_fd = -1;
    }

    /* Disconnect all clients */
    for (int i = 0; i < MAX_CLIENTS; i++) {
        if (server->clients[i].connected) {
            server->clients[i].connected = 0;
            if (server->clients[i].fd >= 0) {
                close(server->clients[i].fd);
                server->clients[i].fd = -1;
            }
        }
    }

    /* Wait briefly for threads to exit */
    usleep(200000);

    /* Free pixmaps */
    for (int i = 0; i < server->pixmap_count; i++) {
        free(server->pixmaps[i].data);
    }

    /* Free framebuffer */
    if (server->framebuffer) {
        free(server->framebuffer);
        server->framebuffer = NULL;
    }

    pthread_mutex_destroy(&server->lock);

    LOGI("X11 server stopped: display :%d", server->display_num);
}

static void destroy_server(X11Server *server) {
    if (!server) return;
    stop_server(server);
    free(server);
}

// ---- JNI Interface ----

JNIEXPORT jlong JNICALL
Java_com_termx_app_terminal_JniX11_nativeStartServer(JNIEnv *env, jclass cls,
    jint displayNum, jint width, jint height) {

    pthread_mutex_lock(&g_global_lock);

    if (displayNum < 0 || displayNum >= MAX_DISPLAYS) {
        pthread_mutex_unlock(&g_global_lock);
        LOGE("Invalid display number: %d", displayNum);
        return 0;
    }

    if (g_servers[displayNum] != NULL) {
        pthread_mutex_unlock(&g_global_lock);
        LOGW("Display :%d already running", displayNum);
        return (jlong)g_servers[displayNum];
    }

    X11Server *server = create_server(displayNum, width, height);
    if (!server) {
        pthread_mutex_unlock(&g_global_lock);
        LOGE("Failed to create server for display :%d", displayNum);
        return 0;
    }

    if (!start_server(server)) {
        destroy_server(server);
        pthread_mutex_unlock(&g_global_lock);
        return 0;
    }

    g_servers[displayNum] = server;
    pthread_mutex_unlock(&g_global_lock);

    return (jlong)server;
}

JNIEXPORT void JNICALL
Java_com_termx_app_terminal_JniX11_nativeStopServer(JNIEnv *env, jclass cls, jlong handle) {
    X11Server *server = (X11Server *)handle;
    if (!server) return;

    pthread_mutex_lock(&g_global_lock);
    if (server->display_num >= 0 && server->display_num < MAX_DISPLAYS) {
        g_servers[server->display_num] = NULL;
    }
    destroy_server(server);
    pthread_mutex_unlock(&g_global_lock);
}

JNIEXPORT jboolean JNICALL
Java_com_termx_app_terminal_JniX11_nativeIsRunning(JNIEnv *env, jclass cls, jlong handle) {
    X11Server *server = (X11Server *)handle;
    return server && server->running ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jlong JNICALL
Java_com_termx_app_terminal_JniX11_nativeGetFramebufferHandle(JNIEnv *env, jclass cls, jlong handle) {
    X11Server *server = (X11Server *)handle;
    if (!server || !server->framebuffer) return 0;
    return (jlong)server->framebuffer;
}

JNIEXPORT jboolean JNICALL
Java_com_termx_app_terminal_JniX11_nativeTakeScreenshot(JNIEnv *env, jclass cls, jlong handle, jstring path) {
    X11Server *server = (X11Server *)handle;
    if (!server || !server->framebuffer) return JNI_FALSE;

    const char *path_str = (*env)->GetStringUTFChars(env, path, NULL);
    if (!path_str) return JNI_FALSE;

    /* Write PPM file (simplest format that doesn't need libpng) */
    FILE *f = fopen(path_str, "wb");
    (*env)->ReleaseStringUTFChars(env, path, path_str);

    if (!f) {
        LOGE("Failed to open screenshot file");
        return JNI_FALSE;
    }

    pthread_mutex_lock(&server->lock);

    fprintf(f, "P6\n%d %d\n255\n", server->width, server->height);
    uint32_t *fb = (uint32_t *)server->framebuffer;
    for (int i = 0; i < server->width * server->height; i++) {
        uint32_t pixel = fb[i];
        unsigned char rgb[3] = {
            (pixel >> 16) & 0xFF,  /* R */
            (pixel >> 8) & 0xFF,   /* G */
            pixel & 0xFF           /* B */
        };
        fwrite(rgb, 1, 3, f);
    }
    fclose(f);

    pthread_mutex_unlock(&server->lock);
    LOGI("Screenshot saved: %s", path_str);
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_termx_app_terminal_JniX11_nativeSendKeyEvent(JNIEnv *env, jclass cls,
    jlong handle, jint keysym, jboolean down) {
    X11Server *server = (X11Server *)handle;
    if (!server) return;

    pthread_mutex_lock(&server->lock);
    for (int i = 0; i < MAX_CLIENTS; i++) {
        XClient *c = &server->clients[i];
        if (!c->connected) continue;
        unsigned char evt[32];
        memset(evt, 0, 32);
        evt[0] = down ? X_Event_KeyPress : X_Event_KeyRelease;
        write_card32(evt + 4, server->root_window, c->byte_order);  /* event window */
        write_card32(evt + 8, server->root_window, c->byte_order);  /* child */
        write_card16(evt + 12, 0, c->byte_order);  /* root_x */
        write_card16(evt + 14, 0, c->byte_order);  /* root_y */
        write_card16(evt + 16, server->mouse_x, c->byte_order);
        write_card16(evt + 18, server->mouse_y, c->byte_order);
        write_card32(evt + 20, 0, c->byte_order);  /* state */
        write_card32(evt + 24, keysym, c->byte_order);  /* keycode = keysym for simplicity */
        evt[28] = 0;  /* same_screen */
        send_event(c, evt);
    }
    pthread_mutex_unlock(&server->lock);
}

JNIEXPORT void JNICALL
Java_com_termx_app_terminal_JniX11_nativeSendPointerEvent(JNIEnv *env, jclass cls,
    jlong handle, jint x, jint y, jint buttonMask) {
    X11Server *server = (X11Server *)handle;
    if (!server) return;

    pthread_mutex_lock(&server->lock);
    server->mouse_x = x;
    server->mouse_y = y;
    server->mouse_buttons = buttonMask;

    /* Send MotionNotify */
    for (int i = 0; i < MAX_CLIENTS; i++) {
        XClient *c = &server->clients[i];
        if (!c->connected) continue;
        unsigned char evt[32];
        memset(evt, 0, 32);
        evt[0] = X_Event_MotionNotify;
        write_card32(evt + 4, server->root_window, c->byte_order);
        write_card32(evt + 8, server->root_window, c->byte_order);
        write_card16(evt + 12, x, c->byte_order);
        write_card16(evt + 14, y, c->byte_order);
        write_card16(evt + 16, x, c->byte_order);
        write_card16(evt + 18, y, c->byte_order);
        write_card32(evt + 24, buttonMask, c->byte_order);
        evt[28] = 1;  /* same_screen */
        send_event(c, evt);
    }

    /* Send ButtonPress/Release for changed buttons */
    static int prev_buttons = 0;
    int changed = buttonMask ^ prev_buttons;
    if (changed) {
        for (int btn = 1; btn <= 3; btn++) {
            if (changed & (1 << (btn - 1))) {
                int pressed = buttonMask & (1 << (btn - 1));
                for (int i = 0; i < MAX_CLIENTS; i++) {
                    XClient *c = &server->clients[i];
                    if (!c->connected) continue;
                    unsigned char evt[32];
                    memset(evt, 0, 32);
                    evt[0] = pressed ? X_Event_ButtonPress : X_Event_ButtonRelease;
                    write_card32(evt + 4, server->root_window, c->byte_order);
                    write_card32(evt + 8, server->root_window, c->byte_order);
                    write_card16(evt + 12, x, c->byte_order);
                    write_card16(evt + 14, y, c->byte_order);
                    write_card16(evt + 16, x, c->byte_order);
                    write_card16(evt + 18, y, c->byte_order);
                    write_card32(evt + 20, 0, c->byte_order);  /* state */
                    write_card32(evt + 24, buttonMask, c->byte_order);  /* state after */
                    evt[28] = btn;  /* detail = button number */
                    send_event(c, evt);
                }
            }
        }
    }
    prev_buttons = buttonMask;

    pthread_mutex_unlock(&server->lock);
}

JNIEXPORT void JNICALL
Java_com_termx_app_terminal_JniX11_nativeResize(JNIEnv *env, jclass cls,
    jlong handle, jint width, jint height) {
    X11Server *server = (X11Server *)handle;
    if (!server) return;

    pthread_mutex_lock(&server->lock);

    int new_size = width * height * 4;
    unsigned char *new_fb = (unsigned char *)calloc(1, new_size);
    if (new_fb) {
        /* Copy old content */
        int copy_w = width < server->width ? width : server->width;
        int copy_h = height < server->height ? height : server->height;
        uint32_t *src = (uint32_t *)server->framebuffer;
        uint32_t *dst = (uint32_t *)new_fb;
        for (int y = 0; y < copy_h; y++) {
            for (int x = 0; x < copy_w; x++) {
                dst[y * width + x] = src[y * server->width + x];
            }
        }
        /* Fill remaining area */
        for (int y = copy_h; y < height; y++) {
            for (int x = 0; x < width; x++) {
                dst[y * width + x] = 0xFF1E1E2E;
            }
        }
        free(server->framebuffer);
        server->framebuffer = new_fb;
        server->width = width;
        server->height = height;
        server->fb_size = new_size;
        server->windows[0].w = width;
        server->windows[0].h = height;
        mark_dirty(server, 0, 0, width, height);

        /* Send ConfigureNotify to all clients */
        send_configure_notify(server, server->root_window);
    }

    pthread_mutex_unlock(&server->lock);
    LOGI("Display :%d resized to %dx%d", server->display_num, width, height);
}

JNIEXPORT jint JNICALL
Java_com_termx_app_terminal_JniX11_nativeGetClientCount(JNIEnv *env, jclass cls, jlong handle) {
    X11Server *server = (X11Server *)handle;
    if (!server) return 0;
    return server->client_count;
}

JNIEXPORT jint JNICALL
Java_com_termx_app_terminal_JniX11_nativeGetWidth(JNIEnv *env, jclass cls, jlong handle) {
    X11Server *server = (X11Server *)handle;
    return server ? server->width : 0;
}

JNIEXPORT jint JNICALL
Java_com_termx_app_terminal_JniX11_nativeGetHeight(JNIEnv *env, jclass cls, jlong handle) {
    X11Server *server = (X11Server *)handle;
    return server ? server->height : 0;
}

JNIEXPORT jint JNICALL
Java_com_termx_app_terminal_JniX11_nativeGetDisplayNum(JNIEnv *env, jclass cls, jlong handle) {
    X11Server *server = (X11Server *)handle;
    return server ? server->display_num : -1;
}

/* Read framebuffer data into a Java byte array (for Kotlin-side rendering) */
JNIEXPORT jboolean JNICALL
Java_com_termx_app_terminal_JniX11_nativeReadFramebuffer(JNIEnv *env, jclass cls,
    jlong handle, jbyteArray outArray, jint offset, jint length) {
    X11Server *server = (X11Server *)handle;
    if (!server || !server->framebuffer) return JNI_FALSE;

    jbyte *out = (*env)->GetByteArrayElements(env, outArray, NULL);
    if (!out) return JNI_FALSE;

    pthread_mutex_lock(&server->lock);
    int copy_len = length < server->fb_size ? length : server->fb_size;
    memcpy(out + offset, server->framebuffer, copy_len);
    pthread_mutex_unlock(&server->lock);

    (*env)->ReleaseByteArrayElements(env, outArray, out, 0);
    return JNI_TRUE;
}
