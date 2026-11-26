#include "Inflate.h"
#include <stdlib.h>
#include <limits.h>
#include <zlib.h>

int GXZInflate(const uint8_t *input, size_t inputSize, uint8_t **output, size_t *outputSize, size_t limit) {
    if (!input || inputSize > UINT_MAX || !limit) return 1;
    *output = NULL; *outputSize = 0;
    for (int attempt = 0; attempt < 2; attempt++) {
        z_stream stream = {0};
        if (inflateInit2(&stream, attempt == 0 ? 15 + 32 : -15) != Z_OK) return 1;
        size_t capacity = limit < 16384 ? limit : 16384;
        uint8_t *buffer = malloc(capacity);
        if (!buffer) { inflateEnd(&stream); return 1; }
        stream.next_in = (Bytef *)input; stream.avail_in = (uInt)inputSize;
        int status = Z_OK;
        while (status == Z_OK) {
            if (stream.total_out == capacity) {
                if (capacity == limit) { status = Z_MEM_ERROR; break; }
                size_t next = capacity > limit / 2 ? limit : capacity * 2;
                uint8_t *grown = realloc(buffer, next);
                if (!grown) { status = Z_MEM_ERROR; break; }
                buffer = grown; capacity = next;
            }
            stream.next_out = buffer + stream.total_out;
            stream.avail_out = (uInt)(capacity - stream.total_out);
            status = inflate(&stream, Z_NO_FLUSH);
        }
        size_t size = stream.total_out;
        inflateEnd(&stream);
        if (status == Z_STREAM_END && size <= limit) { *output = buffer; *outputSize = size; return 0; }
        free(buffer);
    }
    return 1;
}
