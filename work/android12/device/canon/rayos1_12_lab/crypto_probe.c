#include <openssl/crypto.h>
#include <openssl/ec.h>
#include <openssl/ecdsa.h>
#include <openssl/nid.h>
#include <openssl/rand.h>
#include <openssl/sha.h>
#include <stdio.h>
#include <string.h>

int main(void) {
    static const unsigned char expected[SHA256_DIGEST_LENGTH] = {
        0xba,0x78,0x16,0xbf,0x8f,0x01,0xcf,0xea,0x41,0x41,0x40,0xde,0x5d,0xae,0x22,0x23,
        0xb0,0x03,0x61,0xa3,0x96,0x17,0x7a,0x9c,0xb4,0x10,0xff,0x61,0xf2,0x00,0x15,0xad};
    unsigned char a[32], b[32], hash[32], signature[256];
    unsigned signature_size = sizeof(signature);
    if (FIPS_mode() != 0 || RAND_bytes(a, sizeof(a)) != 1 ||
        RAND_bytes(b, sizeof(b)) != 1 || memcmp(a, b, sizeof(a)) == 0) return 1;
    SHA256((const unsigned char*)"abc", 3, hash);
    if (memcmp(hash, expected, sizeof(hash))) return 2;
    EC_KEY* key = EC_KEY_new_by_curve_name(NID_X9_62_prime256v1);
    if (!key || EC_KEY_generate_key(key) != 1 ||
        ECDSA_sign(0, hash, sizeof(hash), signature, &signature_size, key) != 1 ||
        ECDSA_verify(0, hash, sizeof(hash), signature, signature_size, key) != 1) return 3;
    hash[0] ^= 1;
    int rejected = ECDSA_verify(0, hash, sizeof(hash), signature, signature_size, key) != 1;
    EC_KEY_free(key);
    if (!rejected) return 4;
    puts("RAYO12_CRYPTO_NONFIPS_PASS: kernel entropy, SHA-256, P-256 sign/verify and rejection");
    return 0;
}
