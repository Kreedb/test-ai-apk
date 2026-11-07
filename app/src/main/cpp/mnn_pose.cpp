#include <jni.h>

#include <android/asset_manager.h>
#include <android/asset_manager_jni.h>
#include <android/log.h>

#include <MNN/Interpreter.hpp>
#include <MNN/Tensor.hpp>

#include <memory>
#include <vector>

#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "mnnpose", __VA_ARGS__)

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

std::vector<char> readAsset(AAssetManager* manager, const char* path) {
    AAsset* asset = AAssetManager_open(manager, path, AASSET_MODE_BUFFER);
    if (asset == nullptr) {
        return {};
    }
    const off_t length = AAsset_getLength(asset);
    std::vector<char> data(static_cast<size_t>(length));
    const int read = AAsset_read(asset, data.data(), data.size());
    AAsset_close(asset);
    if (read != length) {
        return {};
    }
    return data;
}

struct MnnPose {
    std::unique_ptr<MNN::Interpreter> interpreter;
    MNN::Session* session = nullptr;
    // createSession keeps this pointer, so it must outlive the session.
    MNN::BackendConfig backendConfig;

    ~MnnPose() {
        if (interpreter != nullptr && session != nullptr) {
            interpreter->releaseSession(session);
        }
    }
};

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_example_minimalapk_pose_MnnPose_create(
    JNIEnv* env,
    jobject,
    jobject assetManager,
    jstring modelAsset,
    jint backendType) {
    AAssetManager* manager = AAssetManager_fromJava(env, assetManager);
    const char* modelPath =
        modelAsset == nullptr ? nullptr : env->GetStringUTFChars(modelAsset, nullptr);
    if (manager == nullptr || modelPath == nullptr) {
        if (modelPath != nullptr) env->ReleaseStringUTFChars(modelAsset, modelPath);
        throwIllegalState(env, "AssetManager or model asset path is unavailable");
        return 0;
    }

    auto handle = std::make_unique<MnnPose>();
    // createFromBuffer copies the model bytes, so the buffer does not need to outlive this call.
    std::vector<char> model = readAsset(manager, modelPath);
    env->ReleaseStringUTFChars(modelAsset, modelPath);
    if (model.empty()) {
        throwIllegalState(env, "Failed to read MNN model asset");
        return 0;
    }

    handle->interpreter.reset(MNN::Interpreter::createFromBuffer(model.data(), model.size()));
    if (handle->interpreter == nullptr) {
        throwIllegalState(env, "Failed to create MNN interpreter");
        return 0;
    }

    MNN::ScheduleConfig config;
    config.type = static_cast<MNNForwardType>(backendType);
    config.numThread = 4;
    handle->backendConfig.precision = MNN::BackendConfig::Precision_High;
    config.backendConfig = &handle->backendConfig;
    handle->session = handle->interpreter->createSession(config);
    if (handle->session == nullptr) {
        throwIllegalState(env, "Failed to create MNN session");
        return 0;
    }
    return reinterpret_cast<jlong>(handle.release());
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_example_minimalapk_pose_MnnPose_analyze(
    JNIEnv* env,
    jobject,
    jlong handle,
    jintArray pixels) {
    auto* pose = reinterpret_cast<MnnPose*>(handle);
    if (pose == nullptr) {
        throwIllegalState(env, "MNN model is not loaded");
        return nullptr;
    }

    const jsize length = env->GetArrayLength(pixels);
    if (length != kInputSize * kInputSize) {
        throwIllegalState(env, "Unexpected input pixel count");
        return nullptr;
    }

    MNN::Tensor* input = pose->interpreter->getSessionInput(pose->session, nullptr);
    if (input == nullptr) {
        throwIllegalState(env, "MNN input tensor is missing");
        return nullptr;
    }

    jint* argb = env->GetIntArrayElements(pixels, nullptr);
    if (argb == nullptr) {
        throwIllegalState(env, "Failed to read input pixels");
        return nullptr;
    }
    constexpr float kScale = 1.f / 255.f;
    const int plane = kInputSize * kInputSize;
    std::vector<float> data(static_cast<size_t>(plane) * 3);
    for (jsize i = 0; i < length; ++i) {
        const jint pixel = argb[i];
        data[i] = static_cast<unsigned char>((pixel >> 16) & 0xff) * kScale;
        data[plane + i] = static_cast<unsigned char>((pixel >> 8) & 0xff) * kScale;
        data[plane * 2 + i] = static_cast<unsigned char>(pixel & 0xff) * kScale;
    }
    env->ReleaseIntArrayElements(pixels, argb, JNI_ABORT);

    std::unique_ptr<MNN::Tensor> hostInput(MNN::Tensor::create<float>(
        {1, 3, kInputSize, kInputSize}, data.data(), MNN::Tensor::CAFFE));
    if (!input->copyFromHostTensor(hostInput.get())) {
        throwIllegalState(env, "Failed to copy MNN input");
        return nullptr;
    }
    if (pose->interpreter->runSession(pose->session) != MNN::NO_ERROR) {
        throwIllegalState(env, "MNN inference failed");
        return nullptr;
    }

    MNN::Tensor* output = pose->interpreter->getSessionOutput(pose->session, nullptr);
    if (output == nullptr) {
        throwIllegalState(env, "MNN output tensor is missing");
        return nullptr;
    }
    // Validate the axes too: a transposed 56x8400 output has the same element count but would
    // decode as garbage.
    if (output->dimensions() != 3 || output->length(1) != kAnchorCount ||
        output->length(2) != kValuesPerAnchor) {
        LOGE("Unexpected output shape: dims=%d d1=%d d2=%d",
             output->dimensions(),
             output->dimensions() > 1 ? output->length(1) : -1,
             output->dimensions() > 2 ? output->length(2) : -1);
        throwIllegalState(env, "Unexpected MNN output shape");
        return nullptr;
    }
    std::unique_ptr<MNN::Tensor> hostOutput(
        MNN::Tensor::createHostTensorFromDevice(output, true));
    if (hostOutput == nullptr || hostOutput->elementSize() != kExpectedValues) {
        LOGE("Unexpected output elements: %d",
             hostOutput == nullptr ? -1 : hostOutput->elementSize());
        throwIllegalState(env, "Unexpected MNN output shape");
        return nullptr;
    }

    jfloatArray result = env->NewFloatArray(kExpectedValues);
    if (result != nullptr) {
        env->SetFloatArrayRegion(result, 0, kExpectedValues, hostOutput->host<float>());
    }
    return result;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_example_minimalapk_pose_MnnPose_backendType(JNIEnv*, jobject, jlong handle) {
    auto* pose = reinterpret_cast<MnnPose*>(handle);
    if (pose == nullptr || pose->session == nullptr) {
        return -1;
    }
    // backendType[0] is the primary backend; unsupported ops may still run on backupType.
    int backendType[MNN_FORWARD_ALL] = {};
    if (!pose->interpreter->getSessionInfo(
            pose->session, MNN::Interpreter::BACKENDS, backendType)) {
        return -1;
    }
    return backendType[0];
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_minimalapk_pose_MnnPose_close(JNIEnv*, jobject, jlong handle) {
    delete reinterpret_cast<MnnPose*>(handle);
}
