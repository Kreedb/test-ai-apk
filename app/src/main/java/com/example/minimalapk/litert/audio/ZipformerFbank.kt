/*
 * Copyright 2026 The Google AI Edge Authors. All Rights Reserved.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at https://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND.
 * See the License for the specific language governing permissions and limitations under the License.
 * Adapted from google-ai-edge/litert-samples@f9f0e000630e9cdd6d263c4cd80495e4231a2dc8,
 * samples/litert_model_zoo/android, models/zipformer/ZipformerFbank.kt.
 */
package com.example.minimalapk.litert.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow

/** Kaldi fbank: dither=0, snip_edges=false, high_freq=-400, no CMN, PCM in [-1,1]. */
internal class ZipformerFbank {
    companion object {
        const val SR = 16000
        const val WIN = 400
        const val HOP = 160
        const val NFFT = 512
        const val NBIN = 257
        const val NMEL = 80
        const val LOG_PAD = -23.025851f
        const val MAX_FRAMES = 1600
        const val MAX_SAMPLES = MAX_FRAMES * HOP - HOP / 2
        private const val EPS = 1.1920928955078125e-7f
        private const val PAD = WIN / 2 - HOP / 2

        // Exact float32 nonzero entries of upstream assets/mel80_257.bin (80 x 257).
        // Each row is first-bin:consecutive IEEE754 hex values. No rounded/generated mel banks.
        // Original SHA256 a2f6d8ac704ea144d0e8bcde479f1c53ec674e1ed6fa8094590e64cacee30890.
        private val sparseMel = """
            1:3f0380d4 3dcc743d
            2:3f667178
            3:3f44e050
            3:3e6c7ec2 3efab415
            4:3f02a5f5 3e840360
            5:3f3dfe50 3d8fbb9f
            6:3f6e088c
            7:3f6c72ca
            7:3d9c69b0 3f50bfb2
            8:3e3d0137 3f3e3b8f
            9:3e8388e3 3f3452dd
            10:3e975a46 3f327f50
            11:3e9b0161 3f38474e
            12:3e8f7163 3f453af8
            13:3e6b1420 3f58f3d1
            14:3e1c30bc 3f731416 3d9a2b53
            15:3d4ebea5 3f6cba96 3e64df03
            17:3f46c83f 3ec94257
            18:3f1b5ed5 3f153d7e
            19:3ed58505 3f4accde 3ca292d2
            20:3e54cc88 3f7aeb69 3e87ba66
            22:3f3c22cd 3f06f37e
            23:3ef21904 3f4e27e4 3dca64cc
            24:3e476071 3f66b367 3ed070cd
            26:3f17c799 3f3ac30c 3d863cc0
            27:3e8a79e9 3f6f3868 3ed4463e
            29:3f15dce1 3f46b4a3 3e1975c2
            30:3e652d76 3f59a290 3f08ff7e
            32:3eee0104 3f6e7f8e 3ead8642
            33:3d8c038d 3f293cdf 3f41b147 3e3cca4f
            35:3e793ae5 3f50cd6c 3f1f30dc 3d8cb378
            37:3ec19e48 3f6e6991 3f064f45
            39:3ef36176 3f7d488d 3eecde73
            40:3c2ddcce 3f0990c6 3f71b21d 3ede0151
            42:3d64de2d 3f10ff57 3f6e4a85 3edf0359
            44:3d8dabd4 3f107e53 3f72967f 3eeef6ae
            46:3d569817 3f0884a9 3f7e2382 3f068199 3d844b08
            48:3bee3ec3 3ef2fccd 3f6f769f 3f1c3008 3e25a54a
            51:3ec79ff1 3f5696ae 3f382ad7 3e90d489
            53:3e8faa51 3f3795bb 3f5a1e09 3eda7895 3bddaa18
            55:3e1787dd 3f12c3b6 3f7e44ac 3f17939f 3e3aed2d
            58:3ed0d8c1 3f5144b5 3f472ad1 3ec1b464
            60:3e6354bb 3f1f25ce 3f7bc1e7 3f17da2e 3e5470cf
            62:3c87c31a 3ed04ba4 3f4ae3cc 3f538127 3ee6055d 3d9ccdd7
            65:3e31fb63 3f0cfd52 3f6c6645 3f354096 3eafe1e9
            68:3e957ed3 3f280f0b 3f7ba5ae 3f205841 3e8c0841
            70:3c8b4a39 3ebf4f7e 3f39fbe0 3f6ca3e4 3f14316d 3e72a5c8
            73:3d9ae0e3 3ed79d26 3f43568e 3f660611 3f1042d3 3e6d6edb
            76:3dcfcf77 3edf7a5b 3f44a449 3f674b91 3f140f17 3e8341be
            79:3dc5a377 3ed7e1d3 3f3e5f21 3f6ffe8b 3f1f235d 3e9e16c7
            82:3d800ba4 3ec1b946 3f30f49c 3f7fb318 3f311742 3ec66906 3db03501
            85:3a99d012 3e9dd17d 3f1ccb7d 3f69f960 3f498b3f 3efb7db3 3e4a797e
            89:3e59d302 3f024127 3f4d61a0 3f682687 3f1e54b9 3eaa4bcf 3d497b7d
            92:3dbecbc9 3ec3568e 3f2ada19 3f736848 3f44a6a3 3efaa118 3e5a4c5e
            96:3e6d6573 3f02af74 3f496ce9 3f706abf 3f2ad619 3ecba51f 3e057722
            99:3d795411 3eaa53ce 3f1a2d70 3f5ea238 3f5d74e9 3f1a1610 3eae7ec7 3da77269
            103:3e0a2c5c 3ecbd3e0 3f28c09d 3f6b11b3 3f5320f6 3f11d53b 3ea21359 3d85dbcb
            107:3e337c28 3edc558b 3f2ef653 3f6f4487 3f50e938 3f1190ca 3ea56206 3da23a64
            111:3e3c5b21 3edcde6c 3f2d4efd 3f6bb8b4 3f565247 3f18d0fc 3eb7803a 3df8fd4a
            115:3e26b6e5 3ece5e07 3f243fe3 3f60e057 3f62ecba 3f2726d5 3ed7976e 3e4369bb
            119:3de89a32 3eb1b256 3f143449 3f4f2591 3f76511f 3f3c2e71 3f02715d 3e923003 3d810f2c
            123:3d1aee0f 3e87a31e 3efb1d46 3f36e7ff 3f6fde1b 3f578c37 3f1f56f5 3ecf007f 3e401e75
            128:3e21cf25 3ec15215 3f187fc0 3f4ff863 3f78ea3b 3f422893 3f0bc0c2 3eab63ae 3dffd350
            132:3ce2b891 3e775db5 3ee87e7d 3f2a4e29 3f600596 3f6a998a 3f358e45 3f00d732 3e98e6c3 3dc30f0f
            137:3dab33b4 3e94e377 3efe519b 3f338c9e 3f679e1e 3f64a1c2 3f313211 3efc24af 3e968074 3dc5ddcb
            142:3ddaf1f0 3e9d9bdd 3f01eda9 3f34bfc6 3f674447 3f66836c 3f349752 3f02f67b 3ea33d18 3e0241db
            147:3dcbe49f 3e96d15c 3efa130a 3f2e6174 3f5f6f89 3f6fca59 3f3f4b42 3f0f1332 3ebe417f 3e3dcbe7 39259279
            152:3d81ad38 3e81697c 3ee1d99c 3f20df40 3f508d06 3f7ff5a7 3f50e484 3f220179 3ee6c092 3e8a00f6 3db70528
            158:3e3c6df1 3ebbfd0e 3f0c9fb7 3f3aff85 3f691f5b 3f6901ae 3f3b613c 3f0dffd1 3ec1b80a 3e4fd39c 3ce95de0
            163:3db7f291 3e893d89 3ee4005e 3f1f23fb 3f4c0b19 3f78b511 3f5adc4d 3f2ea96b 3f02b156 3eade546 3e2db90f 3a095981
            169:3e148ecc 3ea2ad29 3efa9d54 3f290d5d 3f5491bc 3f7fddaa 3f550edb 3f2a3268 3eff1bde 3eaa3f1c 3e2b9d11 3b649a3b
            175:3e2bc494 3eab9b2f 3f007211 3f2ae072 3f5518bc 3f7f1b66 3f571717 3f2d7e43 3f04192c 3eb5ceb6 3e47a33c 3d11c978
            181:3e23a3a3 3ea5037a 3ef7cda8 3f2518a5 3f4e1731 3f76e368 3f60823c 3f3819bd 3f0fe229 3ecfb610 3e8007c3 3dc2e96d
            187:3dfbee1e 3e8fcc86 3ee03bae 3f1824f8 3f3ffc1f 3f67a2d2 3f70e50b 3f499b7a 3f22807a 3ef72815 3ea9a987 3e3908b0 3cfba69f
            193:3d71af4e 3e599219 3ebaff0d 3f046bf5 3f2b2b3c 3f51bdd4 3f7822cb 3f61a384 3f3b9683 3f15b44f 3edffac4 3e94e377 3e143fc6
            200:3df2e3df 3e88d2fa 3ed49761 3f10029e 3f358e45 3f5af00f 3f7fd87d 3f5aca6d 3f35e5c2 3f112a03 3ed92d70 3e9056d0 3e0fa04f
            206:3a1e0bd1 3e14d64b 3e94347c 3eddabfa 3f136948 3f37d498 3f5c17ec 3f7fcc42 3f5bd7f4 3f380a37 3f146385 3ee1c4ea 3e9b0efc 3e2946c0 3ce83068
            213:3a4ef717 3e10a031 3e8feb91 3ed738f5 3f0f1d8b 3f327882 3f55ae50 3f78be7d 3f64567f 3f418fb4 3f1eed20 3ef8de79 3eb42a32 3e5f7710 3dae5176
            221:3ddd4c05 3e79c132 3ec225c0 3f0390c3 3f25eae7 3f48223c 3f6a35d1 3f73d963 3f520ae3 3f305f31 3f0ed4e6 3edadad2 3e984db3 3e2c04de 3d1fd732
            228:3d4269d8 3e37d476 3e9f419d 3ee25634 3f129297 3f33d926 3f54fec9 3f76028d 3f6919b6 3f4856eb 3f27b494 3f07323a 3ecd9ec7 3e8d1821 3e19a108 3ccc60cd
        """.trimIndent().lines()

        internal fun melTable(): FloatArray = FloatArray(NMEL * NBIN).also { result ->
            check(sparseMel.size == NMEL)
            sparseMel.forEachIndexed { row, line ->
                val (first, values) = line.split(':')
                values.split(' ').forEachIndexed { i, hex ->
                    result[row * NBIN + first.toInt() + i] = Float.fromBits(hex.toLong(16).toInt())
                }
            }
        }

        // Bit-equivalent to upstream povey400.bin: (nonperiodic Hann)^0.85, float32.
        internal fun window(): FloatArray = FloatArray(WIN) {
            (0.5 - 0.5 * cos(2 * PI * it / (WIN - 1))).pow(0.85).toFloat()
        }
    }

    private val mel = melTable()
    private val win = window()
    private val fft = AudioFft(NFFT)

    fun frames(samples: Int): Int {
        require(samples in 0..MAX_SAMPLES)
        return (samples + HOP / 2) / HOP
    }

    fun compute(pcm: FloatArray, control: AudioControl = AudioControl()): Array<FloatArray> {
        require(pcm.size <= MAX_SAMPLES)
        requireFinite(pcm, "Zipformer PCM")
        val result = Array(frames(pcm.size)) { FloatArray(NMEL) }
        val real = FloatArray(NFFT)
        val imag = FloatArray(NFFT)
        val frame = FloatArray(WIN)
        val power = FloatArray(NBIN)
        for (t in result.indices) {
            if (t % 8 == 0) control.checkpoint()
            var mean = 0f
            for (i in 0 until WIN) {
                frame[i] = pcm[AudioDsp.kaldiIndex(t * HOP + i - PAD, pcm.size)]
                mean += frame[i]
            }
            mean /= WIN
            var previous = frame[0] - mean
            real.fill(0f); imag.fill(0f)
            for (i in 0 until WIN) {
                val current = frame[i] - mean
                real[i] = (current - 0.97f * previous) * win[i]
                previous = current
            }
            fft.transform(real, imag)
            for (k in 0 until NBIN) power[k] = real[k] * real[k] + imag[k] * imag[k]
            for (m in 0 until NMEL) {
                var sum = 0f
                for (k in 0 until NBIN) sum += mel[m * NBIN + k] * power[k]
                result[t][m] = ln(maxOf(sum, EPS))
            }
        }
        return result
    }
}
