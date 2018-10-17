package tests

import java.util.concurrent.TimeUnit
import scala.meta.internal.metals.DismissedNotifications

object DismissedNotificationsSuite extends BaseTablesSuite {
  def notifications: DismissedNotifications = tables.dismissedNotifications
  test("basic") {
    assert(!notifications.Only212Navigation.isDismissed)
    notifications.Only212Navigation.dismiss(1, TimeUnit.DAYS)
    time.elapse(1, TimeUnit.HOURS)
    assert(notifications.Only212Navigation.isDismissed)
    time.elapse(1, TimeUnit.DAYS)
    assert(!notifications.Only212Navigation.isDismissed)
  }

  test("forever") {
    assert(!notifications.Only212Navigation.isDismissed)
    notifications.Only212Navigation.dismissForever()
    assert(notifications.Only212Navigation.isDismissed)
    time.elapse(1, TimeUnit.DAYS)
    assert(notifications.Only212Navigation.isDismissed)
    time.elapse(1000, TimeUnit.DAYS)
    assert(notifications.Only212Navigation.isDismissed)
  }
}
