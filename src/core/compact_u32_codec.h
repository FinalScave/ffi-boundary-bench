#pragma once

#include "core/common_types.h"

namespace ffibb {

ByteBuffer EncodeCompactBytes(const ValueVector &values);
ValueVector DecodeCompactBytes(const ByteBuffer &bytes);

} // namespace ffibb
