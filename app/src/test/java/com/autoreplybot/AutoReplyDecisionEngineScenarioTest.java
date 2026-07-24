package com.autoreplybot;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;

@RunWith(Parameterized.class)
public class AutoReplyDecisionEngineScenarioTest {
    private final Scenario scenario;

    public AutoReplyDecisionEngineScenarioTest(Scenario scenario) {
        this.scenario = scenario;
    }

    @Parameterized.Parameters(name = "{index}: {0}")
    public static Collection<Object[]> scenarios() {
        List<Object[]> rows = new ArrayList<>();
        add(rows, scenario("safe business pricing", ReplyAction.SEND_REPLY)
                .intent(MessageIntent.BUSINESS_PRICING).relationship(RelationshipType.BUSINESS_CUSTOMER));
        add(rows, scenario("safe business lead", ReplyAction.SEND_REPLY)
                .intent(MessageIntent.BUSINESS_SCUBA).relationship(RelationshipType.BUSINESS_LEAD));
        add(rows, scenario("safe unknown business inquiry", ReplyAction.SEND_REPLY)
                .intent(MessageIntent.BUSINESS_AVAILABILITY));
        add(rows, scenario("safe general question", ReplyAction.SEND_REPLY)
                .intent(MessageIntent.GENERAL_QUESTION));
        add(rows, scenario("safe greeting", ReplyAction.SEND_REPLY).intent(MessageIntent.GREETING));
        add(rows, scenario("safe professional conversation", ReplyAction.SEND_REPLY)
                .intent(MessageIntent.PROFESSIONAL_CONVERSATION)
                .relationship(RelationshipType.PROFESSIONAL));
        add(rows, scenario("disabled contact", ReplyAction.NO_REPLY).profileEnabled(false));
        add(rows, scenario("blocked contact", ReplyAction.NO_REPLY)
                .relationship(RelationshipType.BLOCKED));
        add(rows, scenario("manual reply mode", ReplyAction.REQUIRE_APPROVAL)
                .mode(ReplyMode.MANUAL_ONLY));
        add(rows, scenario("manual relationship", ReplyAction.REQUIRE_APPROVAL)
                .relationship(RelationshipType.MANUAL_ONLY));
        add(rows, scenario("local safety no reply", ReplyAction.NO_REPLY)
                .safetyAction(ReplyAction.NO_REPLY));
        add(rows, scenario("generator no reply", ReplyAction.NO_REPLY)
                .generatedAction(ReplyAction.NO_REPLY));
        add(rows, scenario("local safety approval", ReplyAction.REQUIRE_APPROVAL)
                .safetyAction(ReplyAction.REQUIRE_APPROVAL));
        add(rows, scenario("safe model advisory approval", ReplyAction.SEND_REPLY)
                .generatedAction(ReplyAction.REQUIRE_APPROVAL));
        add(rows, scenario("business category disabled", ReplyAction.REQUIRE_APPROVAL)
                .intent(MessageIntent.BUSINESS_PRICING).relationship(RelationshipType.BUSINESS_CUSTOMER)
                .businessEnabled(false));
        add(rows, scenario("business relationship mismatch", ReplyAction.REQUIRE_APPROVAL)
                .intent(MessageIntent.BUSINESS_PRICING).relationship(RelationshipType.FRIEND));
        add(rows, scenario("friend category disabled", ReplyAction.REQUIRE_APPROVAL)
                .intent(MessageIntent.FRIEND_CONVERSATION).relationship(RelationshipType.FRIEND));
        add(rows, scenario("friend category enabled", ReplyAction.SEND_REPLY)
                .intent(MessageIntent.FRIEND_CONVERSATION).relationship(RelationshipType.FRIEND)
                .friendEnabled(true));
        add(rows, scenario("unknown contact with friend-style message", ReplyAction.SEND_REPLY)
                .intent(MessageIntent.FRIEND_CONVERSATION).relationship(RelationshipType.UNKNOWN)
                .friendEnabled(true));
        add(rows, scenario("family category disabled", ReplyAction.REQUIRE_APPROVAL)
                .intent(MessageIntent.FAMILY_CONVERSATION).relationship(RelationshipType.FAMILY));
        add(rows, scenario("family category enabled", ReplyAction.SEND_REPLY)
                .intent(MessageIntent.FAMILY_CONVERSATION).relationship(RelationshipType.FAMILY)
                .familyEnabled(true));
        add(rows, scenario("general questions disabled", ReplyAction.REQUIRE_APPROVAL)
                .intent(MessageIntent.GENERAL_QUESTION).generalEnabled(false));
        add(rows, scenario("medical category", ReplyAction.REQUIRE_APPROVAL)
                .intent(MessageIntent.MEDICAL));
        add(rows, scenario("emergency category", ReplyAction.REQUIRE_APPROVAL)
                .intent(MessageIntent.EMERGENCY));
        add(rows, scenario("legal category", ReplyAction.REQUIRE_APPROVAL)
                .intent(MessageIntent.LEGAL));
        add(rows, scenario("financial category", ReplyAction.REQUIRE_APPROVAL)
                .intent(MessageIntent.FINANCIAL));
        add(rows, scenario("sensitive personal category", ReplyAction.REQUIRE_APPROVAL)
                .intent(MessageIntent.SENSITIVE_PERSONAL));
        add(rows, scenario("spam classification", ReplyAction.NO_REPLY)
                .intent(MessageIntent.SPAM).safetyAction(ReplyAction.NO_REPLY));
        add(rows, scenario("abusive classification", ReplyAction.NO_REPLY)
                .intent(MessageIntent.ABUSIVE).safetyAction(ReplyAction.NO_REPLY));
        add(rows, scenario("unknown classification", ReplyAction.REQUIRE_APPROVAL)
                .intent(MessageIntent.UNKNOWN));
        add(rows, scenario("business context denied", ReplyAction.REQUIRE_APPROVAL)
                .intent(MessageIntent.BUSINESS_PRICING).relationship(RelationshipType.BUSINESS_CUSTOMER)
                .businessContextRequired(true).allowBusinessContext(false));
        add(rows, scenario("personal context denied", ReplyAction.REQUIRE_APPROVAL)
                .personalContextRequired(true).allowPersonalContext(false));
        add(rows, scenario("business context allowed", ReplyAction.SEND_REPLY)
                .intent(MessageIntent.BUSINESS_PRICING).relationship(RelationshipType.BUSINESS_CUSTOMER)
                .businessContextRequired(true).allowBusinessContext(true));
        add(rows, scenario("personal context allowed", ReplyAction.SEND_REPLY)
                .personalContextRequired(true).allowPersonalContext(true));
        add(rows, scenario("low classifier confidence", ReplyAction.REQUIRE_APPROVAL)
                .classifierConfidence(0.79d));
        add(rows, scenario("low generator confidence", ReplyAction.REQUIRE_APPROVAL)
                .generatorConfidence(0.79d));
        add(rows, scenario("safe classifier advisory", ReplyAction.SEND_REPLY)
                .canAutoReply(false));
        add(rows, scenario("custom confidence threshold", ReplyAction.REQUIRE_APPROVAL)
                .classifierConfidence(0.88d).minimumConfidence(0.90d));
        return rows;
    }

    @Test
    public void producesExplicitExpectedAction() {
        UserSettings settings = new UserSettings();
        settings.setBusinessAutoReplyEnabled(scenario.businessEnabled);
        settings.setFriendAutoReplyEnabled(scenario.friendEnabled);
        settings.setFamilyAutoReplyEnabled(scenario.familyEnabled);
        settings.setGeneralQuestionAutoReplyEnabled(scenario.generalEnabled);
        settings.setMinimumAutoReplyConfidence(scenario.minimumConfidence);

        ContactProfile profile = new ContactProfile("contact", "", "Sender",
                scenario.relationship, scenario.profileEnabled, scenario.mode, "auto", "",
                scenario.allowBusinessContext, scenario.allowPersonalContext, 1L, 1L);
        MessageClassificationResult classification = new MessageClassificationResult(
                scenario.intent, scenario.classifierConfidence, "en", "neutral",
                false, false, scenario.businessContextRequired,
                scenario.personalContextRequired, scenario.canAutoReply, "TEST");
        GeneratedReply generated = new GeneratedReply(scenario.generatedAction, "Reply",
                scenario.intent, scenario.generatorConfidence, "TEST", Collections.emptyList(),
                false, false, false, true);
        SafetyClassificationResult safety = new SafetyClassificationResult(
                scenario.safetyAction, InformationClassification.PUBLIC_BUSINESS,
                Collections.emptyList(), false, false, false, "TEST");

        assertEquals(scenario.name, scenario.expected,
                new AutoReplyDecisionEngine().decide(
                        classification, generated, safety, profile, settings));
    }

    private static void add(List<Object[]> rows, Scenario scenario) {
        rows.add(new Object[]{scenario});
    }

    private static Scenario scenario(String name, ReplyAction expected) {
        return new Scenario(name, expected);
    }

    private static final class Scenario {
        final String name;
        final ReplyAction expected;
        MessageIntent intent = MessageIntent.GENERAL_QUESTION;
        RelationshipType relationship = RelationshipType.UNKNOWN;
        ReplyMode mode = ReplyMode.SMART;
        ReplyAction safetyAction = ReplyAction.SEND_REPLY;
        ReplyAction generatedAction = ReplyAction.SEND_REPLY;
        boolean profileEnabled = true;
        boolean businessEnabled = true;
        boolean friendEnabled;
        boolean familyEnabled;
        boolean generalEnabled = true;
        boolean businessContextRequired;
        boolean personalContextRequired;
        boolean allowBusinessContext;
        boolean allowPersonalContext;
        boolean canAutoReply = true;
        double classifierConfidence = 0.95d;
        double generatorConfidence = 0.95d;
        double minimumConfidence = 0.80d;

        Scenario(String name, ReplyAction expected) {
            this.name = name;
            this.expected = expected;
        }

        Scenario intent(MessageIntent value) { intent = value; return this; }
        Scenario relationship(RelationshipType value) { relationship = value; return this; }
        Scenario mode(ReplyMode value) { mode = value; return this; }
        Scenario safetyAction(ReplyAction value) { safetyAction = value; return this; }
        Scenario generatedAction(ReplyAction value) { generatedAction = value; return this; }
        Scenario profileEnabled(boolean value) { profileEnabled = value; return this; }
        Scenario businessEnabled(boolean value) { businessEnabled = value; return this; }
        Scenario friendEnabled(boolean value) { friendEnabled = value; return this; }
        Scenario familyEnabled(boolean value) { familyEnabled = value; return this; }
        Scenario generalEnabled(boolean value) { generalEnabled = value; return this; }
        Scenario businessContextRequired(boolean value) { businessContextRequired = value; return this; }
        Scenario personalContextRequired(boolean value) { personalContextRequired = value; return this; }
        Scenario allowBusinessContext(boolean value) { allowBusinessContext = value; return this; }
        Scenario allowPersonalContext(boolean value) { allowPersonalContext = value; return this; }
        Scenario canAutoReply(boolean value) { canAutoReply = value; return this; }
        Scenario classifierConfidence(double value) { classifierConfidence = value; return this; }
        Scenario generatorConfidence(double value) { generatorConfidence = value; return this; }
        Scenario minimumConfidence(double value) { minimumConfidence = value; return this; }

        @Override public String toString() { return name + " -> " + expected; }
    }
}
