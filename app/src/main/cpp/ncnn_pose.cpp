#include <jni.h>

#include <android/asset_manager.h>
#include <android/asset_manager_jni.h>
#include <android/log.h>

#include <gpu.h>
#include <net.h>

#include <pthread.h>

#include <vector>

#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "ncnnpose", __VA_ARGS__)

namespace {

constexpr int kInputSize = 640;
constexpr int kAnchorCount = 8400;
constexpr int kValuesPerAnchor = 56;
constexpr int kExpectedValues = kAnchorCount * kValuesPerAnchor;

void throwIllegalState(JNIEnv* env, const char* message) {
    jclass clazz = env->FindClass("java/lang/IllegalStateException");
    if (clazz != nullptr) {
        env->ThrowNew(clazz, message);
    }
}

const char* getUtf(JNIEnv* env, jstring value) {
    return value == nullptr ? nullptr : env->GetStringUTFChars(value, nullptr);
}

void createGpuInstance() {
    ncnn::create_gpu_instance();
}

pthread_once_t gpu_instance_once = PTHREAD_ONCE_INIT;

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_example_minimalapk_pose_NcnnPose_create(
    JNIEnv* env,
    jobject,
    jobject assetManager,
    jstring paramAsset,
    jstring modelAsset) {
    AAssetManager* manager = AAssetManager_fromJava(env, assetManager);
    if (manager == nullptr) {
        throwIllegalState(env, "AssetManager is unavailable");
        return 0;
    }

    const char* paramPath = getUtf(env, paramAsset);
    const char* modelPath = getUtf(env, modelAsset);
    if (paramPath == nullptr || modelPath == nullptr) {
        if (paramPath != nullptr) env->ReleaseStringUTFChars(paramAsset, paramPath);
        if (modelPath != nullptr) env->ReleaseStringUTFChars(modelAsset, modelPath);
        throwIllegalState(env, "Model asset path is null");
        return 0;
    }

    // ncnn's Vulkan instance is process-wide: create it once and keep it for the app lifetime.
    pthread_once(&gpu_instance_once, createGpuInstance);

    auto* net = new ncnn::Net();
    // Without a Vulkan device ncnn stays on CPU instead of failing to load the model.
    net->opt.use_vulkan_compute = ncnn::get_gpu_count() > 0;

    int result = net->load_param(manager, paramPath);
    if (result == 0) {
        result = net->load_model(manager, modelPath);
    }

    env->ReleaseStringUTFChars(paramAsset, paramPath);
    env->ReleaseStringUTFChars(modelAsset, modelPath);

    if (result != 0) {
        LOGE("Failed to load ncnn model: %d", result);
        delete net;
        return 0;
    }
    return reinterpret_cast<jlong>(net);
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_example_minimalapk_pose_NcnnPose_analyze(
    JNIEnv* env,
    jobject,
    jlong handle,
    jintArray pixels) {
    auto* net = reinterpret_cast<ncnn::Net*>(handle);
    if (net == nullptr) {
        throwIllegalState(env, "ncnn model is not loaded");
        return nullptr;
    }

    const jsize length = env->GetArrayLength(pixels);
    if (length != kInputSize * kInputSize) {
        throwIllegalState(env, "Unexpected input pixel count");
        return nullptr;
    }

    jint* argb = env->GetIntArrayElements(pixels, nullptr);
    if (argb == nullptr) {
        return nullptr;
    }
    std::vector<unsigned char> rgb(static_cast<size_t>(length) * 3);
    for (jsize i = 0; i < length; ++i) {
        const jint pixel = argb[i];
        rgb[i * 3] = static_cast<unsigned char>((pixel >> 16) & 0xff);
        rgb[i * 3 + 1] = static_cast<unsigned char>((pixel >> 8) & 0xff);
        rgb[i * 3 + 2] = static_cast<unsigned char>(pixel & 0xff);
    }
    env->ReleaseIntArrayElements(pixels, argb, JNI_ABORT);

    ncnn::Mat input =
        ncnn::Mat::from_pixels(rgb.data(), ncnn::Mat::PIXEL_RGB, kInputSize, kInputSize);
    const float norm[3] = {1.f / 255.f, 1.f / 255.f, 1.f / 255.f};
    input.substract_mean_normalize(nullptr, norm);

    ncnn::Extractor extractor = net->create_extractor();
    if (extractor.input("in0", input) != 0) {
        throwIllegalState(env, "ncnn input blob in0 is missing");
        return nullptr;
    }

    ncnn::Mat output;
    if (extractor.extract("out0", output) != 0) {
        throwIllegalState(env, "ncnn output blob out0 is missing");
        return nullptr;
    }
    if (output.total() != kExpectedValues || output.elemsize != 4u || output.elempack != 1) {
        LOGE("Unexpected output: total=%zu elemsize=%zu elempack=%d",
             output.total(), output.elemsize, output.elempack);
        throwIllegalState(env, "Unexpected ncnn output shape");
        return nullptr;
    }

    jfloatArray result = env->NewFloatArray(kExpectedValues);
    if (result != nullptr) {
        env->SetFloatArrayRegion(
            result, 0, kExpectedValues, reinterpret_cast<const jfloat*>(output.data));
    }
    return result;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_minimalapk_pose_NcnnPose_isGpuEnabled(JNIEnv*, jobject, jlong handle) {
    auto* net = reinterpret_cast<ncnn::Net*>(handle);
    return net != nullptr && net->opt.use_vulkan_compute ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_minimalapk_pose_NcnnPose_close(JNIEnv*, jobject, jlong handle) {
    delete reinterpret_cast<ncnn::Net*>(handle);
}
