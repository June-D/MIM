//
//  Swift 与极光 SDK 的桥接头。
//
//  为什么需要它：JPush 的 SwiftPM 包只把静态库链接进来（JPushWrapper 是空的
//  链接器壳），没有生成 Swift 能 import 的模块，`import JPush` 会报
//  「Unable to resolve module dependency」。桥接头是官方在 SPM 场景下推荐的做法。
//
#import "JPUSHService.h"
