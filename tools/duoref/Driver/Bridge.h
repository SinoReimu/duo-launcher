#import <XCTest/XCTest.h>

// Private XCTest event synthesis (the same entry points WebDriverAgent uses): lets the driver
// replay a touch path with exact timing, which the public drag/swipe API does not allow.
@interface XCPointerEventPath : NSObject
- (instancetype)initForTouchAtPoint:(CGPoint)point offset:(double)offset;
- (void)moveToPoint:(CGPoint)point atOffset:(double)offset;
- (void)liftUpAtOffset:(double)offset;
@end

@interface XCSynthesizedEventRecord : NSObject
- (instancetype)initWithName:(NSString *)name interfaceOrientation:(long long)orientation;
- (instancetype)initWithName:(NSString *)name;
- (instancetype)initWithName:(NSString *)name displayID:(int)displayID interfaceOrientation:(long long)orientation;
- (void)addPointerEventPath:(XCPointerEventPath *)path;
- (BOOL)synthesizeWithError:(NSError **)error;
@end

@protocol DuoEventSynthesizer <NSObject>
- (id)synthesizeEvent:(XCSynthesizedEventRecord *)event completion:(void (^)(BOOL, NSError *))completion;
@end

@interface XCUIDevice (DuoPrivate)
- (id<DuoEventSynthesizer>)eventSynthesizer;
@end
