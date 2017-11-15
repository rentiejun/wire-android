/**
  * Wire
  * Copyright (C) 2017 Wire Swiss GmbH
  *
  * This program is free software: you can redistribute it and/or modify
  * it under the terms of the GNU General Public License as published by
  * the Free Software Foundation, either version 3 of the License, or
  * (at your option) any later version.
  *
  * This program is distributed in the hope that it will be useful,
  * but WITHOUT ANY WARRANTY; without even the implied warranty of
  * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
  * GNU General Public License for more details.
  *
  * You should have received a copy of the GNU General Public License
  * along with this program.  If not, see <http://www.gnu.org/licenses/>.
  */
package com.waz.zclient.tracking

import com.mixpanel.android.mpmetrics.MixpanelAPI
import com.waz.ZLog.ImplicitTag._
import com.waz.ZLog._
import com.waz.api.EphemeralExpiration
import com.waz.content.Preferences.PrefKey
import com.waz.content.{GlobalPreferences, MembersStorage, UsersStorage}
import com.waz.model.ConversationData.ConversationType
import com.waz.model.{UserId, _}
import com.waz.service.tracking._
import com.waz.service.{UiLifeCycle, ZMessaging}
import com.waz.threading.{SerialDispatchQueue, Threading}
import com.waz.utils.{RichThreetenBPDuration, _}
import com.waz.utils.events.{EventContext, Signal}
import com.waz.zclient._
import com.waz.zclient.controllers.SignInController.{InputType, SignInMethod}
import com.waz.zclient.tracking.AddPhotoOnRegistrationEvent.Source
import org.json.JSONObject

import scala.concurrent.Future._
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

class GlobalTrackingController(implicit inj: Injector, cxt: WireContext, eventContext: EventContext) extends Injectable {

  import GlobalTrackingController._

  private implicit val dispatcher = new SerialDispatchQueue(name = "Tracking")

  private val superProps = Signal(returning(new JSONObject()) { o =>
    o.put(AppSuperProperty, AppSuperPropertyValue)
  }).disableAutowiring()

  private val mixpanel = MixpanelApiToken.map(MixpanelAPI.getInstance(cxt.getApplicationContext, _))
  verbose(s"For build: ${BuildConfig.APPLICATION_ID}, mixpanel: $mixpanel was created using token: ${BuildConfig.MIXPANEL_APP_TOKEN}")

  //For automation tests
  def getId = mixpanel.map(_.getDistinctId).getOrElse("")

  val zmsOpt = inject[Signal[Option[ZMessaging]]]
  val zMessaging = inject[Signal[ZMessaging]]
  val currentConv = inject[Signal[ConversationData]]

  private val prefKey = BuildConfig.APPLICATION_ID match {
    case "com.wire" | "com.wire.internal" => GlobalPreferences.AnalyticsEnabled
    case _ => PrefKey[Boolean]("DEVELOPER_TRACKING_ENABLED")
  }

  private def trackingEnabled = ZMessaging.globalModule.flatMap(_.prefs.preference(prefKey).apply())

  inject[UiLifeCycle].uiActive.onChanged {
    case false =>
      mixpanel.foreach { m =>
        verbose("flushing mixpanel events")
        m.flush()
      }
    case _ =>
  }

  // access tracking events when they become available and start processing
  ZMessaging.globalModule.map(_.trackingService.events).foreach {
    _ { case (zms, event) => process(event, zms) }
  }

  /**
    * Sets super properties and actually performs the tracking of an event. Super properties are user scoped, so for that
    * reason, we need to ensure they're correctly set based on whatever account (zms) they were fired within.
    */
  private def process(event: TrackingEvent, zms: Option[ZMessaging] = None): Unit = {
    def send() = {
      for {
        sProps <- superProps.head
        teamSize <- zms match {
          case Some(z) => z.teamId.fold(Future.successful(0))(_ => z.teams.searchTeamMembers().head.map(_.size))
          case _ => Future.successful(0)
        }
      } yield {
        mixpanel.foreach { m =>
          //clear account-based super properties
          m.unregisterSuperProperty(TeamInTeamSuperProperty)
          m.unregisterSuperProperty(TeamSizeSuperProperty)

          //set account-based super properties based on supplied zms
          sProps.put(TeamInTeamSuperProperty, zms.flatMap(_.teamId).isDefined)
          sProps.put(TeamSizeSuperProperty, teamSize)

          //register the super properties, and track
          m.registerSuperProperties(sProps)
          m.track(event.name, event.props.orNull)
        }
        verbose(
          s"""
             |trackEvent: ${event.name}
             |properties: ${event.props.map(_.toString(2))}
             |superProps: ${mixpanel.map(_.getSuperProperties).getOrElse(sProps).toString(2)}
          """.stripMargin)
      }
    }

    event match {
      case _: MissedPushEvent =>
        //TODO - re-enable this event when we can reduce their frequency a little. Too many events for mixpanel right now
      case e: ReceivedPushEvent if e.p.toFetch.forall(_.asScala < 10.seconds) =>
        //don't track - there are a lot of these events! We want to keep the event count lower
      case OptEvent(true) =>
        mixpanel.foreach { m =>
          verbose("Opted in to analytics, re-registering")
          m.unregisterSuperProperty(MixpanelIgnoreProperty)
        }
        send()
      case OptEvent(false) =>
        send().map { _ =>
          mixpanel.foreach { m =>
            verbose("Opted out of analytics, flushing and de-registering")
            m.flush()
            m.registerSuperProperties(returning(new JSONObject()) { o =>
              o.put(MixpanelIgnoreProperty, true)
            })
          }
        }
      case ExceptionEvent(_, _, description, Some(throwable)) =>
        trackingEnabled.map {
          case true =>
            HockeyApp.saveException(throwable, description)
            send()
          case _ => //no action
        }
      case _ =>
        trackingEnabled.map {
          case true => send()
          case _ => //no action
        }
    }
  }

  private def responseToErrorPair(response: Either[EntryError, Unit]) = response.fold({ e => Option((e.code, e.label))}, _ => Option.empty[(Int, String)])
  private def track(event: TrackingEvent) = ZMessaging.globalModule.map(_.trackingService.track(event))

  //Should wait until a ZMS instance exists before firing the event
  def onEnteredCredentials(response: Either[EntryError, Unit], method: SignInMethod): Unit = {
      for {
        acc <- ZMessaging.currentAccounts.activeAccount.head
        invToken = acc.flatMap(_.invitationToken)
      } yield {
        //TODO when are generic tokens still used?
        track(EnteredCredentialsEvent(method, responseToErrorPair(response), invToken))
      }
  }

  def onEnterCode(response: Either[EntryError, Unit], method: SignInMethod): Unit =
    track(EnteredCodeEvent(method, responseToErrorPair(response)))

  def onRequestResendCode(response: Either[EntryError, Unit], method: SignInMethod, isCall: Boolean): Unit =
    track(ResendVerificationEvent(method, isCall, responseToErrorPair(response)))

  def onAddNameOnRegistration(response: Either[EntryError, Unit], inputType: InputType): Unit =
    track(EnteredNameOnRegistrationEvent(inputType, responseToErrorPair(response)))

  def onAddPhotoOnRegistration(inputType: InputType, source: Source, response: Either[EntryError, Unit] = Right(())): Unit =
    track(AddPhotoOnRegistrationEvent(inputType, responseToErrorPair(response), source))

  def flushEvents(): Unit = mixpanel.foreach(_.flush())
}

object GlobalTrackingController {

  private lazy val MixpanelIgnoreProperty = "$ignore"

  private lazy val AppSuperProperty = "app"
  private lazy val AppSuperPropertyValue = "android"
  private lazy val TeamInTeamSuperProperty = "team.in_team"
  private lazy val TeamSizeSuperProperty = "team.size"


  //For build flavours that don't have tracking enabled, this should be None
  private lazy val MixpanelApiToken = Option(BuildConfig.MIXPANEL_APP_TOKEN).filter(_.nonEmpty)

  def isBot(conv: ConversationData, users: UsersStorage): Future[Boolean] =
    if (conv.convType == ConversationType.OneToOne) users.get(UserId(conv.id.str)).map(_.exists(_.isWireBot))(Threading.Background)
    else successful(false)

  //TODO remove workarounds for 1:1 team conversations when supported on backend
  def convType(conv: ConversationData, membersStorage: MembersStorage)(implicit executionContext: ExecutionContext): Future[ConversationType] =
  if (conv.team.isEmpty) Future.successful(conv.convType)
  else membersStorage.getByConv(conv.id).map(_.map(_.userId).size > 2).map {
    case true => ConversationType.Group
    case _ => ConversationType.OneToOne
  }

  case class AssetTrackingData(conversationType: ConversationType, withOtto: Boolean, expiration: EphemeralExpiration, assetSize: Long, mime: Mime)

}
