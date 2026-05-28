/*
 * Copyright (C) 2026 The LineageOS Project
 *
 * SPDX-License-Identifier: Apache-2.0
 */

#define LOG_TAG "xiaomi-sm8750-vibrator"

#include "Vibrator.h"

#include <android/binder_status.h>
#include <dirent.h>
#include <fcntl.h>
#include <linux/input.h>
#include <log/log.h>
#include <sys/ioctl.h>
#include <unistd.h>

#include <array>
#include <cerrno>
#include <chrono>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <iterator>
#include <thread>

namespace aidl {
namespace android {
namespace hardware {
namespace vibrator {

namespace {

constexpr char kInputDir[] = "/dev/input";
constexpr char kInputPrefix[] = "event";
constexpr char kSysfsInputDir[] = "/sys/class/input";
constexpr char kVibeStateSuffix[] = "/device/default/vibe_state";
constexpr int16_t kInvalidEffect = -1;
constexpr uint16_t kBuzzPeriodMs = 5;
constexpr uint16_t kRamWaveformBank = 0;
constexpr uint16_t kWaveformLong = 0;
constexpr uint16_t kWaveformClick = 2;
constexpr uint16_t kWaveformShort = 3;
constexpr uint16_t kWaveformThud = 4;
constexpr uint16_t kWaveformQuickRise = 6;
constexpr uint16_t kWaveformKeyboardTick = 7;
constexpr uint16_t kWaveformQuickFall = 8;
constexpr int32_t kMaxTimeoutMs = UINT16_MAX;
constexpr int32_t kComposeDelayMaxMs = 1000;
constexpr int32_t kComposeSizeMax = 16;
constexpr int32_t kDoubleClickPulseMs = 12;
constexpr int32_t kDoubleClickPeriodMs = 55;
constexpr int32_t kGestureTickDurationMs = 16;
constexpr int32_t kKeyboardTickDurationMs = 10;
constexpr int32_t kEffectCleanupDelayMs = 80;
constexpr int32_t kCachedEffectMaxDurationMs = 40;
constexpr int32_t kVibeStateStartTimeoutMs = 40;
constexpr int32_t kVibeStateStopSlackMs = 80;
constexpr int32_t kVibeStatePollStepMs = 2;
constexpr int kVibeStateStopped = 0;
constexpr int kVibeStateHaptic = 1;

constexpr uint8_t kLightGainPct = 40;
constexpr uint8_t kMediumGainPct = 60;
constexpr uint8_t kStrongGainPct = 82;
constexpr uint8_t kGestureTickGainPct = 80;
constexpr uint8_t kKeyboardTickGainPct = 75;

bool shouldCacheWaveform(uint16_t waveformIndex, int32_t timeoutMs) {
  return waveformIndex != kWaveformLong && timeoutMs > 0 &&
         timeoutMs <= kCachedEffectMaxDurationMs;
}

bool testBit(int bit, const unsigned long *array) {
  return (array[bit / (sizeof(unsigned long) * 8)] &
          (1UL << (bit % (sizeof(unsigned long) * 8)))) != 0;
}

bool hasPrefix(const char *value, const char *prefix) {
  return strncmp(value, prefix, strlen(prefix)) == 0;
}

bool isSupportedInputName(const char *name) {
  return strcmp(name, "cs40l26_input") == 0 ||
         strcmp(name, "cs40l26_vibra") == 0 ||
         strcmp(name, "cs40l26_dual_input") == 0;
}

ndk::ScopedAStatus unsupported() {
  return ndk::ScopedAStatus::fromExceptionCode(EX_UNSUPPORTED_OPERATION);
}

ndk::ScopedAStatus serviceError(int error) {
  return ndk::ScopedAStatus::fromServiceSpecificError(error < 0 ? -error
                                                                : error);
}

} // namespace

bool Vibrator::CachedEffectKey::operator<(const CachedEffectKey &other) const {
  if (waveformIndex != other.waveformIndex) {
    return waveformIndex < other.waveformIndex;
  }
  if (durationMs != other.durationMs) {
    return durationMs < other.durationMs;
  }
  return gainPct < other.gainPct;
}

Vibrator::Vibrator() {
  std::lock_guard lock(mLock);
  openInputLocked();
}

Vibrator::~Vibrator() {
  std::lock_guard lock(mLock);
  eraseEffectLocked();
  eraseCachedEffectsLocked();
  if (mFd >= 0) {
    close(mFd);
    mFd = -1;
  }
}

bool Vibrator::openInputLocked() {
  if (mFd >= 0) {
    return true;
  }

  DIR *dir = opendir(kInputDir);
  if (dir == nullptr) {
    ALOGE("Failed to open %s: %s", kInputDir, strerror(errno));
    return false;
  }

  dirent *entry;
  while ((entry = readdir(dir)) != nullptr) {
    if (!hasPrefix(entry->d_name, kInputPrefix)) {
      continue;
    }

    std::string path = std::string(kInputDir) + "/" + entry->d_name;
    int fd = TEMP_FAILURE_RETRY(open(path.c_str(), O_RDWR | O_CLOEXEC));
    if (fd < 0) {
      ALOGD("Failed to open %s: %s", path.c_str(), strerror(errno));
      continue;
    }

    char name[256] = {};
    if (TEMP_FAILURE_RETRY(ioctl(fd, EVIOCGNAME(sizeof(name)), name)) < 0) {
      ALOGD("Failed to read input name for %s: %s", path.c_str(),
            strerror(errno));
      close(fd);
      continue;
    }

    if (!isSupportedInputName(name)) {
      close(fd);
      continue;
    }

    unsigned long ffBits[(FF_MAX / (sizeof(unsigned long) * 8)) + 1] = {};
    if (TEMP_FAILURE_RETRY(
            ioctl(fd, EVIOCGBIT(EV_FF, sizeof(ffBits)), ffBits)) < 0) {
      ALOGE("Failed to read FF capabilities for %s: %s", path.c_str(),
            strerror(errno));
      close(fd);
      continue;
    }

    if (!testBit(FF_PERIODIC, ffBits) ||
        (!testBit(FF_CUSTOM, ffBits) && !testBit(FF_SINE, ffBits))) {
      ALOGE("%s at %s lacks usable force-feedback waveform support", name,
            path.c_str());
      close(fd);
      continue;
    }

    mFd = fd;
    mInputPath = path;
    mInputName = name;
    mHasCustom = testBit(FF_CUSTOM, ffBits);
    mHasGain = testBit(FF_GAIN, ffBits);
    const char *eventName = strrchr(path.c_str(), '/');
    eventName = eventName == nullptr ? path.c_str() : eventName + 1;
    mVibeStatePath =
        std::string(kSysfsInputDir) + "/" + eventName + kVibeStateSuffix;
    mHasVibeState = access(mVibeStatePath.c_str(), R_OK) == 0;
    ALOGI("Using %s at %s, custom=%d gain=%d sine=%d period=%ums",
          mInputName.c_str(), mInputPath.c_str(), mHasCustom, mHasGain,
          testBit(FF_SINE, ffBits), kBuzzPeriodMs);
    preloadCachedEffectsLocked();
    closedir(dir);
    return true;
  }

  closedir(dir);
  ALOGE("No supported CS40L26 force-feedback input device found");
  return false;
}

int Vibrator::uploadCachedWaveformLocked(const CachedEffectKey &key) {
  auto cached = mEffectCache.find(key);
  if (cached != mEffectCache.end()) {
    return cached->second;
  }

  int16_t customData[] = {
      static_cast<int16_t>(kRamWaveformBank),
      static_cast<int16_t>(key.waveformIndex),
  };

  ff_effect effect = {};
  effect.type = FF_PERIODIC;
  effect.id = kInvalidEffect;
  effect.u.periodic.waveform = FF_CUSTOM;
  effect.u.periodic.magnitude = key.gainPct;
  effect.u.periodic.custom_data = customData;
  effect.u.periodic.custom_len = std::size(customData);
  effect.replay.length = static_cast<uint16_t>(key.durationMs);
  effect.replay.delay = 0;

  if (TEMP_FAILURE_RETRY(ioctl(mFd, EVIOCSFF, &effect)) < 0) {
    int error = errno;
    ALOGW("Failed to cache RAM waveform %u/%dms/%u%%: %s", key.waveformIndex,
          key.durationMs, key.gainPct, strerror(error));
    return -error;
  }

  mEffectCache[key] = effect.id;
  ALOGI("Cached RAM waveform %u/%dms/%u%% as FF effect %d", key.waveformIndex,
        key.durationMs, key.gainPct, effect.id);
  return effect.id;
}

void Vibrator::preloadCachedEffectsLocked() {
  if (mPreloadedEffects || !mHasCustom) {
    return;
  }

  mPreloadedEffects = true;
  const std::array<CachedEffectKey, 7> kPreloadEffects = {{
      {kWaveformClick, 12, kLightGainPct},
      {kWaveformClick, 12, kMediumGainPct},
      {kWaveformClick, 12, kStrongGainPct},
      {kWaveformClick, kGestureTickDurationMs, 75},
      {kWaveformClick, kGestureTickDurationMs, kGestureTickGainPct},
      {kWaveformClick, kGestureTickDurationMs, 92},
      {kWaveformKeyboardTick, kKeyboardTickDurationMs, kKeyboardTickGainPct},
  }};

  for (const CachedEffectKey &key : kPreloadEffects) {
    uploadCachedWaveformLocked(key);
  }
}

void Vibrator::eraseCachedEffectsLocked() {
  if (mFd < 0) {
    mEffectCache.clear();
    return;
  }

  for (const auto &cachedEffect : mEffectCache) {
    const CachedEffectKey &key = cachedEffect.first;
    int16_t effect = cachedEffect.second;
    if (TEMP_FAILURE_RETRY(ioctl(mFd, EVIOCRMFF, effect)) < 0) {
      ALOGW("Failed to erase cached RAM waveform %u/%dms/%u%% effect %d: %s",
            key.waveformIndex, key.durationMs, key.gainPct, effect,
            strerror(errno));
    }
  }

  mEffectCache.clear();
}

int Vibrator::eraseEffectLocked() {
  if (mFd < 0 || mCurrentEffect == kInvalidEffect) {
    mCurrentEffect = kInvalidEffect;
    mCurrentEffectCached = false;
    return 0;
  }

  int effect = mCurrentEffect;
  bool cached = mCurrentEffectCached;
  mCurrentEffect = kInvalidEffect;
  mCurrentEffectCached = false;

  input_event stop = {};
  stop.type = EV_FF;
  stop.code = effect;
  stop.value = 0;
  ssize_t written = TEMP_FAILURE_RETRY(write(mFd, &stop, sizeof(stop)));
  if (written != static_cast<ssize_t>(sizeof(stop))) {
    int error = written < 0 ? errno : EIO;
    ALOGW("Failed to stop FF effect %d before cleanup: %s", effect,
          strerror(error));
  }

  if (cached) {
    return 0;
  }

  if (TEMP_FAILURE_RETRY(ioctl(mFd, EVIOCRMFF, effect)) < 0) {
    int error = errno;
    ALOGE("Failed to erase FF effect %d: %s", effect, strerror(error));
    return -error;
  }

  return 0;
}

int Vibrator::setGainLocked(uint8_t gainPct) {
  if (!mHasGain) {
    return 0;
  }

  input_event gain = {};
  gain.type = EV_FF;
  gain.code = FF_GAIN;
  gain.value = gainPct;

  ssize_t written = TEMP_FAILURE_RETRY(write(mFd, &gain, sizeof(gain)));
  if (written != static_cast<ssize_t>(sizeof(gain))) {
    int error = written < 0 ? errno : EIO;
    ALOGE("Failed to set FF_GAIN %u%%: %s", gainPct, strerror(error));
    return -error;
  }

  return 0;
}

int Vibrator::playWaveformLocked(uint16_t waveformIndex, int32_t timeoutMs,
                                 uint8_t gainPct) {
  if (timeoutMs <= 0 || timeoutMs > kMaxTimeoutMs) {
    return -EINVAL;
  }

  if (!openInputLocked()) {
    return -ENODEV;
  }

  if (!mHasCustom) {
    return -ENOTSUP;
  }

  int ret = eraseEffectLocked();
  if (ret != 0) {
    return ret;
  }

  ret = setGainLocked(gainPct);
  if (ret != 0) {
    return ret;
  }

  if (shouldCacheWaveform(waveformIndex, timeoutMs)) {
    CachedEffectKey key = {waveformIndex, timeoutMs, gainPct};
    int effect = uploadCachedWaveformLocked(key);
    if (effect < 0) {
      return effect;
    }

    mCurrentEffect = effect;
    mCurrentEffectCached = true;

    input_event play = {};
    play.type = EV_FF;
    play.code = mCurrentEffect;
    play.value = 1;

    ssize_t written = TEMP_FAILURE_RETRY(write(mFd, &play, sizeof(play)));
    if (written != static_cast<ssize_t>(sizeof(play))) {
      int error = written < 0 ? errno : EIO;
      ALOGE("Failed to start cached RAM waveform %u effect %d: %s",
            waveformIndex, mCurrentEffect, strerror(error));
      mCurrentEffect = kInvalidEffect;
      mCurrentEffectCached = false;
      return -error;
    }

    return 0;
  }

  int16_t customData[] = {
      static_cast<int16_t>(kRamWaveformBank),
      static_cast<int16_t>(waveformIndex),
  };

  ff_effect effect = {};
  effect.type = FF_PERIODIC;
  effect.id = kInvalidEffect;
  effect.u.periodic.waveform = FF_CUSTOM;
  effect.u.periodic.magnitude = gainPct;
  effect.u.periodic.custom_data = customData;
  effect.u.periodic.custom_len = std::size(customData);
  effect.replay.length = static_cast<uint16_t>(timeoutMs);
  effect.replay.delay = 0;

  if (TEMP_FAILURE_RETRY(ioctl(mFd, EVIOCSFF, &effect)) < 0) {
    int error = errno;
    ALOGE("Failed to upload RAM waveform %u: %s", waveformIndex,
          strerror(error));
    return -error;
  }

  mCurrentEffect = effect.id;
  mCurrentEffectCached = false;

  input_event play = {};
  play.type = EV_FF;
  play.code = mCurrentEffect;
  play.value = 1;

  ssize_t written = TEMP_FAILURE_RETRY(write(mFd, &play, sizeof(play)));
  if (written != static_cast<ssize_t>(sizeof(play))) {
    int error = written < 0 ? errno : EIO;
    ALOGE("Failed to start RAM waveform %u effect %d: %s", waveformIndex,
          mCurrentEffect, strerror(error));
    eraseEffectLocked();
    return -error;
  }

  return 0;
}

int Vibrator::playSineLocked(int32_t timeoutMs, uint8_t level) {
  if (timeoutMs <= 0 || timeoutMs > kMaxTimeoutMs) {
    return -EINVAL;
  }

  if (!openInputLocked()) {
    return -ENODEV;
  }

  int ret = eraseEffectLocked();
  if (ret != 0) {
    return ret;
  }

  ff_effect effect = {};
  effect.type = FF_PERIODIC;
  effect.id = kInvalidEffect;
  effect.u.periodic.waveform = FF_SINE;
  effect.u.periodic.period = kBuzzPeriodMs;
  effect.u.periodic.magnitude = level;
  effect.replay.length = static_cast<uint16_t>(timeoutMs);
  effect.replay.delay = 0;

  if (TEMP_FAILURE_RETRY(ioctl(mFd, EVIOCSFF, &effect)) < 0) {
    int error = errno;
    ALOGE("Failed to upload FF_SINE effect: %s", strerror(error));
    return -error;
  }

  mCurrentEffect = effect.id;
  mCurrentEffectCached = false;

  input_event play = {};
  play.type = EV_FF;
  play.code = mCurrentEffect;
  play.value = 1;

  ssize_t written = TEMP_FAILURE_RETRY(write(mFd, &play, sizeof(play)));
  if (written != static_cast<ssize_t>(sizeof(play))) {
    int error = written < 0 ? errno : EIO;
    ALOGE("Failed to start FF effect %d: %s", mCurrentEffect, strerror(error));
    eraseEffectLocked();
    return -error;
  }

  return 0;
}

int Vibrator::playHapticLocked(uint16_t waveformIndex, int32_t timeoutMs,
                               uint8_t gainPct) {
  int ret = playWaveformLocked(waveformIndex, timeoutMs, gainPct);
  if (ret == 0 || ret == -ENODEV) {
    return ret;
  }

  ALOGW("Falling back to FF_SINE for waveform %u after error: %s",
        waveformIndex, strerror(-ret));
  return playSineLocked(timeoutMs, gainPct);
}

int Vibrator::readVibeState() const {
  if (!mHasVibeState || mVibeStatePath.empty()) {
    return -ENODEV;
  }

  int fd =
      TEMP_FAILURE_RETRY(open(mVibeStatePath.c_str(), O_RDONLY | O_CLOEXEC));
  if (fd < 0) {
    return -errno;
  }

  char value[16] = {};
  ssize_t bytes = TEMP_FAILURE_RETRY(read(fd, value, sizeof(value) - 1));
  int error = bytes < 0 ? errno : 0;
  close(fd);
  if (bytes <= 0) {
    return error == 0 ? -EIO : -error;
  }

  char *end = nullptr;
  long state = strtol(value, &end, 10);
  if (end == value) {
    return -EINVAL;
  }

  return static_cast<int>(state);
}

bool Vibrator::pollVibeState(int expectedState, int32_t timeoutMs) const {
  auto deadline =
      std::chrono::steady_clock::now() + std::chrono::milliseconds(timeoutMs);
  do {
    if (readVibeState() == expectedState) {
      return true;
    }
    std::this_thread::sleep_for(
        std::chrono::milliseconds(kVibeStatePollStepMs));
  } while (std::chrono::steady_clock::now() < deadline);

  return readVibeState() == expectedState;
}

void Vibrator::completeEffectAsync(
    const std::shared_ptr<IVibratorCallback> &callback, int32_t durationMs,
    uint64_t generation, bool useVibeState) {
  std::thread([this, callback, durationMs, generation, useVibeState] {
    bool completed = false;
    if (useVibeState && mHasVibeState &&
        pollVibeState(kVibeStateHaptic, kVibeStateStartTimeoutMs)) {
      completed =
          pollVibeState(kVibeStateStopped, durationMs + kEffectCleanupDelayMs +
                                               kVibeStateStopSlackMs);
    }

    if (!completed) {
      std::this_thread::sleep_for(std::chrono::milliseconds(durationMs));
    }

    if (mGeneration.load() != generation) {
      return;
    }

    if (callback != nullptr && !callback->onComplete().isOk()) {
      ALOGE("Failed to notify vibration completion");
    }

    if (!completed) {
      std::this_thread::sleep_for(
          std::chrono::milliseconds(kEffectCleanupDelayMs));
    }

    if (mGeneration.load() != generation) {
      return;
    }

    {
      std::lock_guard lock(mLock);
      if (mGeneration.load() != generation) {
        return;
      }

      int ret = eraseEffectLocked();
      if (ret != 0) {
        ALOGE("Failed to clean up completed haptic effect: %s", strerror(-ret));
      }
    }
  }).detach();
}

void Vibrator::scheduleFollowupHaptic(uint64_t generation, int32_t delayMs,
                                      uint16_t waveformIndex,
                                      int32_t durationMs, uint8_t gainPct) {
  std::thread([this, generation, delayMs, waveformIndex, durationMs, gainPct] {
    std::this_thread::sleep_for(std::chrono::milliseconds(delayMs));
    if (mGeneration.load() != generation) {
      return;
    }

    std::lock_guard lock(mLock);
    if (mGeneration.load() != generation) {
      return;
    }

    int ret = playHapticLocked(waveformIndex, durationMs, gainPct);
    if (ret != 0) {
      ALOGE("Failed to play follow-up haptic pulse: %s", strerror(-ret));
    }
  }).detach();
}

int32_t Vibrator::durationForEffect(Effect effect) const {
  switch (effect) {
  case Effect::CLICK:
    return 12;
  case Effect::TICK:
  case Effect::TEXTURE_TICK:
    return kGestureTickDurationMs;
  case Effect::DOUBLE_CLICK:
    return kDoubleClickPeriodMs + kDoubleClickPulseMs;
  case Effect::THUD:
    return 32;
  case Effect::POP:
    return 16;
  case Effect::HEAVY_CLICK:
    return 18;
  default:
    return 0;
  }
}

uint16_t Vibrator::waveformForEffect(Effect effect) const {
  switch (effect) {
  case Effect::CLICK:
  case Effect::DOUBLE_CLICK:
  case Effect::HEAVY_CLICK:
    return kWaveformClick;
  case Effect::TICK:
  case Effect::TEXTURE_TICK:
    return kWaveformClick;
  case Effect::THUD:
    return kWaveformThud;
  case Effect::POP:
    return kWaveformQuickFall;
  default:
    return kWaveformShort;
  }
}

uint8_t Vibrator::gainForEffect(Effect effect, EffectStrength strength) const {
  if (effect == Effect::TICK || effect == Effect::TEXTURE_TICK) {
    switch (strength) {
    case EffectStrength::LIGHT:
      return 75;
    case EffectStrength::MEDIUM:
      return kGestureTickGainPct;
    case EffectStrength::STRONG:
      return 92;
    default:
      return 0;
    }
  }

  int gain;
  switch (strength) {
  case EffectStrength::LIGHT:
    gain = kLightGainPct;
    break;
  case EffectStrength::MEDIUM:
    gain = kMediumGainPct;
    break;
  case EffectStrength::STRONG:
    gain = kStrongGainPct;
    break;
  default:
    return 0;
  }

  switch (effect) {
  case Effect::TICK:
  case Effect::TEXTURE_TICK:
    gain -= 5;
    break;
  case Effect::POP:
    gain -= 5;
    break;
  case Effect::HEAVY_CLICK:
  case Effect::THUD:
    gain += 8;
    break;
  default:
    break;
  }

  if (gain < 25) {
    gain = 25;
  } else if (gain > 92) {
    gain = 92;
  }

  return static_cast<uint8_t>(gain);
}

int32_t Vibrator::durationForPrimitive(CompositePrimitive primitive) const {
  switch (primitive) {
  case CompositePrimitive::NOOP:
    return 0;
  case CompositePrimitive::CLICK:
    return 12;
  case CompositePrimitive::THUD:
    return 32;
  case CompositePrimitive::QUICK_RISE:
  case CompositePrimitive::QUICK_FALL:
    return 16;
  case CompositePrimitive::LIGHT_TICK:
    return kGestureTickDurationMs;
  case CompositePrimitive::LOW_TICK:
    return kKeyboardTickDurationMs;
  default:
    return 0;
  }
}

uint16_t Vibrator::waveformForPrimitive(CompositePrimitive primitive) const {
  switch (primitive) {
  case CompositePrimitive::CLICK:
    return kWaveformClick;
  case CompositePrimitive::THUD:
    return kWaveformThud;
  case CompositePrimitive::QUICK_RISE:
    return kWaveformQuickRise;
  case CompositePrimitive::QUICK_FALL:
    return kWaveformQuickFall;
  case CompositePrimitive::LIGHT_TICK:
    return kWaveformClick;
  case CompositePrimitive::LOW_TICK:
    return kWaveformKeyboardTick;
  default:
    return kWaveformShort;
  }
}

uint8_t Vibrator::gainForPrimitive(CompositePrimitive primitive,
                                   float scale) const {
  if (scale <= 0.0f || primitive == CompositePrimitive::NOOP) {
    return 0;
  }

  if (primitive == CompositePrimitive::LIGHT_TICK) {
    return kGestureTickGainPct;
  }
  if (primitive == CompositePrimitive::LOW_TICK && scale >= 0.95f) {
    return kKeyboardTickGainPct;
  }

  int gain = static_cast<int>(kStrongGainPct * scale);
  switch (primitive) {
  case CompositePrimitive::LIGHT_TICK:
    gain += 8;
    break;
  case CompositePrimitive::LOW_TICK:
    gain -= 4;
    break;
  case CompositePrimitive::THUD:
    gain += 8;
    break;
  default:
    break;
  }

  if (primitive == CompositePrimitive::LIGHT_TICK ||
      primitive == CompositePrimitive::LOW_TICK) {
    if (gain < 45) {
      gain = 45;
    }
  } else if (gain < 30) {
    gain = 30;
  }

  if (gain > 92) {
    gain = 92;
  }

  return static_cast<uint8_t>(gain);
}

ndk::ScopedAStatus Vibrator::getCapabilities(int32_t *_aidl_return) {
  *_aidl_return = IVibrator::CAP_ON_CALLBACK | IVibrator::CAP_PERFORM_CALLBACK |
                  IVibrator::CAP_COMPOSE_EFFECTS;
  return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus Vibrator::off() {
  std::lock_guard lock(mLock);
  mGeneration++;
  int ret = eraseEffectLocked();
  return ret == 0 ? ndk::ScopedAStatus::ok() : serviceError(ret);
}

ndk::ScopedAStatus
Vibrator::on(int32_t timeoutMs,
             const std::shared_ptr<IVibratorCallback> &callback) {
  uint64_t generation;
  {
    std::lock_guard lock(mLock);
    generation = ++mGeneration;
    int ret = playHapticLocked(kWaveformLong, timeoutMs, kStrongGainPct);
    if (ret != 0) {
      return serviceError(ret);
    }
  }

  completeEffectAsync(callback, timeoutMs, generation, true);
  return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus
Vibrator::perform(Effect effect, EffectStrength strength,
                  const std::shared_ptr<IVibratorCallback> &callback,
                  int32_t *_aidl_return) {
  int32_t durationMs = durationForEffect(effect);
  uint16_t waveformIndex = waveformForEffect(effect);
  uint8_t gainPct = gainForEffect(effect, strength);
  if (durationMs == 0 || gainPct == 0) {
    return unsupported();
  }

  uint64_t generation;
  {
    std::lock_guard lock(mLock);
    generation = ++mGeneration;
    int ret = playHapticLocked(
        waveformIndex,
        effect == Effect::DOUBLE_CLICK ? kDoubleClickPulseMs : durationMs,
        gainPct);
    if (ret != 0) {
      return serviceError(ret);
    }
  }

  if (effect == Effect::DOUBLE_CLICK) {
    scheduleFollowupHaptic(generation, kDoubleClickPeriodMs, waveformIndex,
                           kDoubleClickPulseMs, gainPct);
  }

  *_aidl_return = durationMs;
  completeEffectAsync(callback, durationMs, generation,
                      effect != Effect::DOUBLE_CLICK);
  return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus
Vibrator::getSupportedEffects(std::vector<Effect> *_aidl_return) {
  *_aidl_return = {
      Effect::CLICK, Effect::DOUBLE_CLICK, Effect::TICK,        Effect::THUD,
      Effect::POP,   Effect::HEAVY_CLICK,  Effect::TEXTURE_TICK};
  return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus Vibrator::setAmplitude(float /*amplitude*/) {
  return unsupported();
}

ndk::ScopedAStatus Vibrator::setExternalControl(bool /*enabled*/) {
  return unsupported();
}

ndk::ScopedAStatus Vibrator::getCompositionDelayMax(int32_t *maxDelayMs) {
  *maxDelayMs = kComposeDelayMaxMs;
  return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus Vibrator::getCompositionSizeMax(int32_t *maxSize) {
  *maxSize = kComposeSizeMax;
  return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus
Vibrator::getSupportedPrimitives(std::vector<CompositePrimitive> *supported) {
  *supported = {CompositePrimitive::NOOP,       CompositePrimitive::CLICK,
                CompositePrimitive::THUD,       CompositePrimitive::QUICK_RISE,
                CompositePrimitive::QUICK_FALL, CompositePrimitive::LIGHT_TICK,
                CompositePrimitive::LOW_TICK};
  return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus Vibrator::getPrimitiveDuration(CompositePrimitive primitive,
                                                  int32_t *durationMs) {
  int32_t duration = durationForPrimitive(primitive);
  if (primitive != CompositePrimitive::NOOP && duration == 0) {
    return unsupported();
  }

  *durationMs = duration;
  return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus
Vibrator::compose(const std::vector<CompositeEffect> &composite,
                  const std::shared_ptr<IVibratorCallback> &callback) {
  if (composite.empty() || composite.size() > kComposeSizeMax) {
    return ndk::ScopedAStatus::fromExceptionCode(EX_ILLEGAL_ARGUMENT);
  }

  int32_t totalDurationMs = 0;
  int32_t playableEffects = 0;
  bool hasDelayedEffect = false;
  for (const CompositeEffect &effect : composite) {
    if (effect.delayMs < 0 || effect.delayMs > kComposeDelayMaxMs ||
        effect.scale < 0.0f || effect.scale > 1.0f) {
      return ndk::ScopedAStatus::fromExceptionCode(EX_ILLEGAL_ARGUMENT);
    }

    int32_t primitiveDuration = durationForPrimitive(effect.primitive);
    if (effect.primitive != CompositePrimitive::NOOP &&
        primitiveDuration == 0) {
      return unsupported();
    }

    if (effect.primitive != CompositePrimitive::NOOP && effect.scale > 0.0f) {
      playableEffects++;
      hasDelayedEffect =
          hasDelayedEffect || effect.delayMs > 0 || totalDurationMs > 0;
    }

    totalDurationMs += effect.delayMs + primitiveDuration;
  }

  uint64_t generation;
  int32_t startMs = 0;
  {
    std::lock_guard lock(mLock);
    generation = ++mGeneration;
    for (const CompositeEffect &effect : composite) {
      startMs += effect.delayMs;

      uint8_t gainPct = gainForPrimitive(effect.primitive, effect.scale);
      if (effect.primitive != CompositePrimitive::NOOP && gainPct > 0) {
        uint16_t waveformIndex = waveformForPrimitive(effect.primitive);
        int32_t durationMs = durationForPrimitive(effect.primitive);
        if (startMs == 0) {
          int ret = playHapticLocked(waveformIndex, durationMs, gainPct);
          if (ret != 0) {
            return serviceError(ret);
          }
        } else {
          scheduleFollowupHaptic(generation, startMs, waveformIndex, durationMs,
                                 gainPct);
        }
      }

      startMs += durationForPrimitive(effect.primitive);
    }
  }

  completeEffectAsync(callback, totalDurationMs, generation,
                      playableEffects == 1 && !hasDelayedEffect);
  return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus
Vibrator::getSupportedAlwaysOnEffects(std::vector<Effect> *_aidl_return) {
  _aidl_return->clear();
  return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus Vibrator::alwaysOnEnable(int32_t /*id*/, Effect /*effect*/,
                                            EffectStrength /*strength*/) {
  return unsupported();
}

ndk::ScopedAStatus Vibrator::alwaysOnDisable(int32_t /*id*/) {
  return unsupported();
}

ndk::ScopedAStatus Vibrator::getResonantFrequency(float * /*resonantFreqHz*/) {
  return unsupported();
}

ndk::ScopedAStatus Vibrator::getQFactor(float * /*qFactor*/) {
  return unsupported();
}

ndk::ScopedAStatus
Vibrator::getFrequencyResolution(float * /*freqResolutionHz*/) {
  return unsupported();
}

ndk::ScopedAStatus Vibrator::getFrequencyMinimum(float * /*freqMinimumHz*/) {
  return unsupported();
}

ndk::ScopedAStatus
Vibrator::getBandwidthAmplitudeMap(std::vector<float> * /*_aidl_return*/) {
  return unsupported();
}

ndk::ScopedAStatus
Vibrator::getPwlePrimitiveDurationMax(int32_t * /*durationMs*/) {
  return unsupported();
}

ndk::ScopedAStatus Vibrator::getPwleCompositionSizeMax(int32_t * /*maxSize*/) {
  return unsupported();
}

ndk::ScopedAStatus
Vibrator::getSupportedBraking(std::vector<Braking> *supported) {
  supported->clear();
  return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus
Vibrator::composePwle(const std::vector<PrimitivePwle> & /*composite*/,
                      const std::shared_ptr<IVibratorCallback> & /*callback*/) {
  return unsupported();
}

} // namespace vibrator
} // namespace hardware
} // namespace android
} // namespace aidl
