package tests

import bench.AkkaSources
import scala.meta.io.AbsolutePath

object WorkspaceSymbolRegressionSuite extends BaseWorkspaceSymbolSuite {
  def workspace: AbsolutePath = AkkaSources.download()
  check("Actor", "1025 results")
  check("Actor(", "")
  check(
    "FSMFB",
    """
      |akka.japi.pf.FSMStateFunctionBuilder Class
      |akka.persistence.fsm.japi.pf.FSMStateFunctionBuilder Class
    """.stripMargin
  )
  check(
    "fsmb",
    """|akka.japi.pf.FSMStateFunctionBuilder Class
       |akka.japi.pf.FSMStopBuilder Class
       |akka.japi.pf.FSMTransitionHandlerBuilder Class
       |akka.persistence.fsm.AbstractPersistentFSMBase Class
       |akka.persistence.fsm.AbstractPersistentFSMBase Object
       |akka.persistence.fsm.PersistentFSMBase Interface
       |akka.persistence.fsm.japi.pf.FSMStateFunctionBuilder Class
       |akka.persistence.fsm.japi.pf.FSMStopBuilder Class
       |akka.persistence.serialization.MessageFormats#PersistentFSMSnapshotOrBuilder Interface
       |""".stripMargin
  )
  // Making lowercase queries "more precise" doesn't help because it grows the search state.
  check(
    "fsmbuilder",
    ""
  )
  check(
    "FSM",
    """|akka.actor.AbstractFSM Class
       |akka.actor.AbstractFSM Object
       |akka.actor.AbstractFSMActorTest Class
       |akka.actor.AbstractFSMActorTest#MyFSM Class
       |akka.actor.AbstractFSMWithStash Class
       |akka.actor.AbstractLoggingFSM Class
       |akka.actor.FSM Interface
       |akka.actor.FSM Object
       |akka.actor.FSMActorSpec Class
       |akka.actor.FSMActorSpec Object
       |akka.actor.FSMTimingSpec Class
       |akka.actor.FSMTimingSpec Object
       |akka.actor.FSMTransitionSpec Class
       |akka.actor.FSMTransitionSpec Object
       |akka.actor.FSMTransitionSpec.MyFSM Class
       |akka.actor.FSMTransitionSpec.OtherFSM Class
       |akka.actor.FSMTransitionSpec.SendAnyTransitionFSM Class
       |akka.actor.LoggingFSM Interface
       |akka.actor.typed.internal.EarliestFirstSystemMessageList Class
       |akka.actor.typed.internal.LatestFirstSystemMessageList Class
       |akka.cluster.RestartFirstSeedNodeMultiJvmNode1 Class
       |akka.cluster.RestartFirstSeedNodeMultiJvmNode2 Class
       |akka.cluster.RestartFirstSeedNodeMultiJvmNode3 Class
       |akka.cluster.RestartFirstSeedNodeMultiJvmSpec Object
       |akka.dispatch.sysmsg.EarliestFirstSystemMessageList Class
       |akka.dispatch.sysmsg.LatestFirstSystemMessageList Class
       |akka.japi.pf.FSMStateFunctionBuilder Class
       |akka.japi.pf.FSMStopBuilder Class
       |akka.japi.pf.FSMTransitionHandlerBuilder Class
       |akka.persistence.fsm.AbstractPersistentFSM Class
       |akka.persistence.fsm.AbstractPersistentFSMBase Class
       |akka.persistence.fsm.AbstractPersistentFSMBase Object
       |akka.persistence.fsm.AbstractPersistentFSMTest Class
       |akka.persistence.fsm.AbstractPersistentFSMTest#PFSMState Class
       |akka.persistence.fsm.AbstractPersistentFSMTest#PFSMwithLog Class
       |akka.persistence.fsm.AbstractPersistentFSMTest#WebStoreCustomerFSM Class
       |akka.persistence.fsm.AbstractPersistentLoggingFSM Class
       |akka.persistence.fsm.InmemPersistentFSMSpec Class
       |akka.persistence.fsm.LeveldbPersistentFSMSpec Class
       |akka.persistence.fsm.LoggingPersistentFSM Interface
       |akka.persistence.fsm.PersistentFSM Interface
       |akka.persistence.fsm.PersistentFSM Object
       |akka.persistence.fsm.PersistentFSM.FSMState Interface
       |akka.persistence.fsm.PersistentFSM.PersistentFSMSnapshot Class
       |akka.persistence.fsm.PersistentFSMBase Interface
       |akka.persistence.fsm.PersistentFSMSpec Class
       |akka.persistence.fsm.PersistentFSMSpec Object
       |akka.persistence.fsm.PersistentFSMSpec.SimpleTransitionFSM Class
       |akka.persistence.fsm.PersistentFSMSpec.SimpleTransitionFSM Object
       |akka.persistence.fsm.PersistentFSMSpec.TimeoutFSM Object
       |akka.persistence.fsm.PersistentFSMSpec.TimeoutFSM.State#SnapshotFSM Class
       |akka.persistence.fsm.PersistentFSMSpec.TimeoutFSM.State#SnapshotFSM Object
       |akka.persistence.fsm.PersistentFSMSpec.TimeoutFSM.State#SnapshotFSMEvent Interface
       |akka.persistence.fsm.PersistentFSMSpec.TimeoutFSM.State#SnapshotFSMState Interface
       |akka.persistence.fsm.PersistentFSMSpec.TimeoutFSM.State#TimeoutFSM Class
       |akka.persistence.fsm.PersistentFSMSpec.WebStoreCustomerFSM Class
       |akka.persistence.fsm.PersistentFSMSpec.WebStoreCustomerFSM Object
       |akka.persistence.fsm.japi.pf.FSMStateFunctionBuilder Class
       |akka.persistence.fsm.japi.pf.FSMStopBuilder Class
       |akka.persistence.serialization.MessageFormats#PersistentFSMSnapshot Class
       |akka.persistence.serialization.MessageFormats#PersistentFSMSnapshotOrBuilder Interface
       |akka.remote.artery.FanInThroughputSpecMultiJvmNode1 Class
       |akka.remote.artery.FanInThroughputSpecMultiJvmNode2 Class
       |akka.remote.artery.FanInThroughputSpecMultiJvmNode3 Class
       |akka.remote.artery.FanInThroughputSpecMultiJvmNode4 Class
       |akka.remote.artery.FanOutThroughputSpecMultiJvmNode1 Class
       |akka.remote.artery.FanOutThroughputSpecMultiJvmNode2 Class
       |akka.remote.artery.FanOutThroughputSpecMultiJvmNode3 Class
       |akka.remote.artery.FanOutThroughputSpecMultiJvmNode4 Class
       |akka.remote.artery.SurviveInboundStreamRestartWithCompressionInFlightSpecMultiJvmNode1 Class
       |akka.remote.artery.SurviveInboundStreamRestartWithCompressionInFlightSpecMultiJvmNode2 Class
       |akka.remote.testconductor.ClientFSM Class
       |akka.remote.testconductor.ClientFSM Object
       |akka.remote.testconductor.Controller.CreateServerFSM Class
       |akka.remote.testconductor.ServerFSM Class
       |akka.remote.testconductor.ServerFSM Object
       |akka.stream.scaladsl.FlowStatefulMapConcatSpec Class
       |akka.testkit.TestFSMRef Class
       |akka.testkit.TestFSMRef Object
       |akka.testkit.TestFSMRefSpec Class
       |akka.testkit.TestFSMRefSpec#TestFSMActor Class
       |docs.actor.FSMDocSpec Class
       |docs.actor.FSMDocSpec Object
       |docs.actor.FSMDocSpec#DemoCode.Dummy#Tick.MyFSM Class
       |docs.akka.typed.FSMDocSpec Class
       |docs.akka.typed.FSMDocSpec Object
       |jdocs.actor.fsm.FSMDocTest Class
       |jdocs.actor.fsm.FSMDocTest#DummyFSM Class
       |jdocs.actor.fsm.FSMDocTest#MyFSM Class
       |jdocs.akka.typed.FSMDocTest Class
    """.stripMargin
  )

}
