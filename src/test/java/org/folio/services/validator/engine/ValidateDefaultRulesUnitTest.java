package org.folio.services.validator.engine;

import static org.hamcrest.MatcherAssert.assertThat;

import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.common.ConsoleNotifier;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.junit.WireMockRule;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import org.apache.http.HttpStatus;
import org.folio.rest.RestVerticle;
import org.folio.rest.impl.GenericHandlerAnswer;
import org.folio.rest.jaxrs.model.Rule;
import org.folio.rest.jaxrs.model.RuleCollection;
import org.folio.services.validator.registry.ValidatorRegistryService;
import org.hamcrest.Matchers;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentMatchers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.mockito.Spy;

import java.util.*;
import java.util.stream.Stream;

import static org.folio.services.validator.util.ValidatorHelper.RESPONSE_ERROR_MESSAGES_KEY;
import static org.folio.services.validator.util.ValidatorHelper.RESPONSE_VALIDATION_RESULT_KEY;
import static org.folio.services.validator.util.ValidatorHelper.VALIDATION_INVALID_RESULT;
import static org.folio.services.validator.util.ValidatorHelper.VALIDATION_VALID_RESULT;

@RunWith(VertxUnitRunner.class)
public class ValidateDefaultRulesUnitTest {
  public static final String MOD_PASSWORD_VALIDATOR = "mod-password-validator";
  private static final String OKAPI_HEADER_TENANT_VALUE = "tenant";
  private static final String OKAPI_HEADER_TOKEN_VALUE = "token";
  private static final String USER_ID_VALUE = "db6ffb67-3160-43bf-8e2f-ecf9a420288b";
  private static final String OKAPI_URL_HEADER = "x-okapi-url";

  private static final JsonObject USER_SERVICE_MOCK_RESPONSE = new JsonObject()
    .put("users", new JsonArray()
      .add(new JsonObject()
        .put("username", "admin")
        .put("id", "9d990cae-2685-4868-9fca-d0ad013c0640")
        .put("active", true)))
    .put("totalRecords", 1);

  private static final Rule REG_MINIMUM_LENGTH_RULE = new Rule()
    .withRuleId("5105b55a-b9a3-4f76-9402-a5243ea63c95")
    .withName("password_length")
    .withType(Rule.Type.REG_EXP)
    .withValidationType(Rule.ValidationType.STRONG)
    .withState(Rule.State.ENABLED)
    .withModuleName(MOD_PASSWORD_VALIDATOR)
    .withExpression("^.{8,}$")
    .withDescription("The password length must be minimum 8 digits")
    .withOrderNo(0)
    .withErrMessageId("password.length.invalid");

  private static final Rule REG_ALPHABETICAL_LETTERS_RULE = new Rule()
    .withRuleId("dc653de8-f0df-48ab-9630-13aacfe8e8f4")
    .withName("alphabetical_letters")
    .withType(Rule.Type.REG_EXP)
    .withValidationType(Rule.ValidationType.STRONG)
    .withState(Rule.State.ENABLED)
    .withModuleName(MOD_PASSWORD_VALIDATOR)
    .withExpression("(?=.*[a-z])(?=.*[A-Z]).+")
    .withDescription("The password must contain both upper and lower case letters")
    .withOrderNo(1)
    .withErrMessageId("password.alphabetical.invalid");

  private static final Rule REG_NUMERIC_SYMBOL_RULE = new Rule()
    .withRuleId("3e3c53ae-73c2-4eba-9f09-f2c9a892c7a2")
    .withName("numeric_symbol")
    .withType(Rule.Type.REG_EXP)
    .withValidationType(Rule.ValidationType.STRONG)
    .withState(Rule.State.ENABLED)
    .withModuleName(MOD_PASSWORD_VALIDATOR)
    .withExpression("(?=.*\\d).+")
    .withDescription("The password must contain at least one numeric character")
    .withOrderNo(2)
    .withErrMessageId("password.number.invalid");

  private static final Rule REG_SPECIAL_CHARACTER_RULE = new Rule()
    .withRuleId("2e82f890-49e8-46fc-923d-644f33dc5c3f")
    .withName("special_character")
    .withType(Rule.Type.REG_EXP)
    .withValidationType(Rule.ValidationType.STRONG)
    .withState(Rule.State.ENABLED)
    .withModuleName(MOD_PASSWORD_VALIDATOR)
    .withExpression("(?=.*[!\"#$%&'()*+,-./:;<=>?@\\[\\]^_`{|}~]).+")
    .withDescription("The password must contain at least one special character")
    .withOrderNo(3)
    .withErrMessageId("password.specialCharacter.invalid");

  private static final Rule REG_USER_NAME_RULE = new Rule()
    .withRuleId("2f390fa6-a2f8-4027-abaf-ee61952668bc")
    .withName("no_user_name")
    .withType(Rule.Type.REG_EXP)
    .withValidationType(Rule.ValidationType.STRONG)
    .withState(Rule.State.ENABLED)
    .withModuleName(MOD_PASSWORD_VALIDATOR)
    .withExpression("^(?:(?!<USER_NAME>).)+$")
    .withDescription("The password must not contain your username")
    .withOrderNo(4)
    .withErrMessageId("password.usernameDuplicate.invalid");

  private static final Rule REG_CONSECUTIVE_WHITESPACES_RULE = new Rule()
    .withRuleId("093f090f-543e-4a04-8b0f-9bde947a390d")
    .withName("no_consecutive_whitespaces")
    .withType(Rule.Type.REG_EXP)
    .withValidationType(Rule.ValidationType.STRONG)
    .withState(Rule.State.ENABLED)
    .withModuleName(MOD_PASSWORD_VALIDATOR)
    .withExpression("^(?:(?!\\s{2,}).)+$")
    .withDescription("The password must not contain multiple consecutive whitespaces")
    .withOrderNo(9)
    .withErrMessageId("password.consecutiveWhitespaces.invalid");

  private static RuleCollection regExpRuleCollection;

  @org.junit.Rule
  public WireMockRule userMockServer = new WireMockRule(
    WireMockConfiguration.wireMockConfig()
      .dynamicPort()
      .notifier(new ConsoleNotifier(true)));

  @Mock
  private ValidatorRegistryService validatorRegistryService;
  @Spy
  private WebClient webClient = WebClient.create(Vertx.vertx());

  @InjectMocks
  private ValidationEngineService validationEngineService = new ValidationEngineServiceImpl();
  private Map<String, String> requestHeaders;

  @BeforeClass
  public static void setUpBeforeClass() {
    initRegExpRules();
  }

  @Before
  public void setUp() throws Exception {
    MockitoAnnotations.openMocks(this).close();
    requestHeaders = new HashMap<>();
    requestHeaders.put(RestVerticle.OKAPI_HEADER_TENANT, OKAPI_HEADER_TENANT_VALUE);
    requestHeaders.put(RestVerticle.OKAPI_HEADER_TOKEN, OKAPI_HEADER_TOKEN_VALUE);
    requestHeaders.put(OKAPI_URL_HEADER, "http://localhost:" + userMockServer.port());
    mockUserModule(HttpStatus.SC_OK, USER_SERVICE_MOCK_RESPONSE);
  }

  private static void initRegExpRules() {
    regExpRuleCollection = new RuleCollection();

    List<Rule> rulesList = new ArrayList<>();
    rulesList.add(REG_MINIMUM_LENGTH_RULE);
    rulesList.add(REG_ALPHABETICAL_LETTERS_RULE);
    rulesList.add(REG_NUMERIC_SYMBOL_RULE);
    rulesList.add(REG_SPECIAL_CHARACTER_RULE);
    rulesList.add(REG_USER_NAME_RULE);
    rulesList.add(REG_CONSECUTIVE_WHITESPACES_RULE);
    regExpRuleCollection.setRules(rulesList);
  }

  @Test
  public void shouldFailWhenUserResponseIncorrectFormat(TestContext testContext) {
    //given
    mockRegistryServiceResponse(JsonObject.mapFrom(regExpRuleCollection));

    //expect
    Handler<AsyncResult<JsonObject>> checkingHandler = testContext.asyncAssertFailure(response -> {
      String validationResult = response.getMessage();
      assertThat(validationResult, Matchers.is("Error, missing field(s) 'totalRecords' and/or 'users' in user response object"));
    });

    // case 1 - no "users" in response
    JsonObject userResponse = new JsonObject()
      .put("user", new JsonArray())
      .put("totalRecords", 1);

    mockUserModule(HttpStatus.SC_OK, userResponse);

    //when
    validationEngineService.validatePassword(USER_ID_VALUE, "P@sw0rd1", requestHeaders, checkingHandler);

    // case 2 - no "totalRecords" in response
    JsonObject userResponse1 = new JsonObject()
      .put("users", new JsonArray())
      .put("total", 1);

    mockUserModule(HttpStatus.SC_OK, userResponse1);

    //when
    validationEngineService.validatePassword(USER_ID_VALUE, "P@sw0rd1", requestHeaders, checkingHandler);
  }

  @Test
  public void shouldFailWhenUserResponseFailed(TestContext testContext) {
    //given
    mockRegistryServiceResponse(JsonObject.mapFrom(regExpRuleCollection));

    mockUserModule(HttpStatus.SC_BAD_REQUEST, new JsonObject());

    //expect
    Handler<AsyncResult<JsonObject>> checkingHandlerBadRequest = testContext.asyncAssertFailure(response -> {
      String validationResult = response.getMessage();
      assertThat(validationResult, Matchers.is("Error getting user by user id : " + USER_ID_VALUE));
    });

    //when
    validationEngineService.validatePassword(USER_ID_VALUE, "P@sw0rd1", requestHeaders, checkingHandlerBadRequest);
  }

  @Test
  public void shouldReturnOkWhenPasswordMatchesRules(TestContext testContext) {
    //given
    String password = "P@sw0rd1";
    mockRegistryServiceResponse(JsonObject.mapFrom(regExpRuleCollection));

    //expect
    Handler<AsyncResult<JsonObject>> checkingHandler = testContext.asyncAssertSuccess(response -> {
      String validationResult = response.getString(RESPONSE_VALIDATION_RESULT_KEY);
      assertThat(validationResult, Matchers.is(VALIDATION_VALID_RESULT));
      JsonArray errorMessages = response.getJsonArray(RESPONSE_ERROR_MESSAGES_KEY);
      assertThat(errorMessages, Matchers.emptyIterable());
    });

    //when
    validationEngineService.validatePassword(USER_ID_VALUE, password, requestHeaders, checkingHandler);
  }

  @Test
  public void shouldFailWhenDoNotMatchPasswordLength(TestContext testContext) {
    // given
    String password = "P@sw0rd";
    mockRegistryServiceResponse(JsonObject.mapFrom(regExpRuleCollection));

    //expect
    Handler<AsyncResult<JsonObject>> checkingHandler = testContext.asyncAssertSuccess(response -> {
      String validationResult = response.getString(RESPONSE_VALIDATION_RESULT_KEY);
      assertThat(validationResult, Matchers.is(VALIDATION_INVALID_RESULT));
      JsonArray errorMessages = response.getJsonArray(RESPONSE_ERROR_MESSAGES_KEY);
      assertThat(errorMessages, Matchers.contains(REG_MINIMUM_LENGTH_RULE.getErrMessageId()));
    });

    //when
    validationEngineService.validatePassword(USER_ID_VALUE, password, requestHeaders, checkingHandler);
  }

  @Test
  public void shouldFailWhenDoNotHaveUpperCaseLetter(TestContext testContext) {
    // given
    String password = "p@sw0rds";
    mockRegistryServiceResponse(JsonObject.mapFrom(regExpRuleCollection));

    //expect
    Handler<AsyncResult<JsonObject>> checkingHandler = testContext.asyncAssertSuccess(response -> {
      String validationResult = response.getString(RESPONSE_VALIDATION_RESULT_KEY);
      assertThat(validationResult, Matchers.is(VALIDATION_INVALID_RESULT));
      JsonArray errorMessages = response.getJsonArray(RESPONSE_ERROR_MESSAGES_KEY);
      assertThat(errorMessages, Matchers.contains(REG_ALPHABETICAL_LETTERS_RULE.getErrMessageId()));
    });

    //when
    validationEngineService.validatePassword(USER_ID_VALUE, password, requestHeaders, checkingHandler);
  }

  @Test
  public void shouldFailWhenDoNotHaveLowerCaseLetter(TestContext testContext) {
    // given
    String password = "P@SW0RDS";
    mockRegistryServiceResponse(JsonObject.mapFrom(regExpRuleCollection));

    //expect
    Handler<AsyncResult<JsonObject>> checkingHandler = testContext.asyncAssertSuccess(response -> {
      String validationResult = response.getString(RESPONSE_VALIDATION_RESULT_KEY);
      assertThat(validationResult, Matchers.is(VALIDATION_INVALID_RESULT));
      JsonArray errorMessages = response.getJsonArray(RESPONSE_ERROR_MESSAGES_KEY);
      assertThat(errorMessages, Matchers.contains(REG_ALPHABETICAL_LETTERS_RULE.getErrMessageId()));
    });

    //when
    validationEngineService.validatePassword(USER_ID_VALUE, password, requestHeaders, checkingHandler);

  }

  @Test
  public void shouldFailWhenNoNumberCharacter(TestContext testContext) {
    // given
    String password = "p@sWords";
    mockRegistryServiceResponse(JsonObject.mapFrom(regExpRuleCollection));

    //expect
    Handler<AsyncResult<JsonObject>> checkingHandler = testContext.asyncAssertSuccess(response -> {
      String validationResult = response.getString(RESPONSE_VALIDATION_RESULT_KEY);
      assertThat(validationResult, Matchers.is(VALIDATION_INVALID_RESULT));
      JsonArray errorMessages = response.getJsonArray(RESPONSE_ERROR_MESSAGES_KEY);
      Assert.assertEquals(1, errorMessages.getList().size());
      assertThat(errorMessages, Matchers.contains(REG_NUMERIC_SYMBOL_RULE.getErrMessageId()));
    });

    //when
    validationEngineService.validatePassword(USER_ID_VALUE, password, requestHeaders, checkingHandler);
  }

  @Test
  public void shouldFailWhenNoSpecialCharacter(TestContext testContext) {
    // given
    String password = "pasW0rds";
    mockRegistryServiceResponse(JsonObject.mapFrom(regExpRuleCollection));

    //expect
    Handler<AsyncResult<JsonObject>> checkingHandler = testContext.asyncAssertSuccess(response -> {
      String validationResult = response.getString(RESPONSE_VALIDATION_RESULT_KEY);
      assertThat(validationResult, Matchers.is(VALIDATION_INVALID_RESULT));
      JsonArray errorMessages = response.getJsonArray(RESPONSE_ERROR_MESSAGES_KEY);
      assertThat(errorMessages, Matchers.contains(REG_SPECIAL_CHARACTER_RULE.getErrMessageId()));
    });

    //when
    validationEngineService.validatePassword(USER_ID_VALUE, password, requestHeaders, checkingHandler);
  }

  @Test
  public void shouldFailWhenPasswordContainsUserName(TestContext testContext) {
    // given
    String password = "P@swadmin0rd1";
    mockRegistryServiceResponse(JsonObject.mapFrom(regExpRuleCollection));

    //expect
    Handler<AsyncResult<JsonObject>> checkingHandler = testContext.asyncAssertSuccess(response -> {
      String validationResult = response.getString(RESPONSE_VALIDATION_RESULT_KEY);
      assertThat(validationResult, Matchers.is(VALIDATION_INVALID_RESULT));
      JsonArray errorMessages = response.getJsonArray(RESPONSE_ERROR_MESSAGES_KEY);
      assertThat(errorMessages, Matchers.contains(REG_USER_NAME_RULE.getErrMessageId()));
    });

    //when
    validationEngineService.validatePassword(USER_ID_VALUE, password, requestHeaders, checkingHandler);
  }

  @Test
  public void shouldNotFailWhenPasswordContainsSequence(TestContext testContext) {
    // given
    String password = "p@sw0qwertyrD";
    mockRegistryServiceResponse(JsonObject.mapFrom(regExpRuleCollection));

    //expect
    Handler<AsyncResult<JsonObject>> checkingHandler = testContext.asyncAssertSuccess(response -> {
      String validationResult = response.getString(RESPONSE_VALIDATION_RESULT_KEY);
      assertThat(validationResult, Matchers.is(VALIDATION_VALID_RESULT));
      JsonArray errorMessages = response.getJsonArray(RESPONSE_ERROR_MESSAGES_KEY);
      assertThat(errorMessages, Matchers.emptyIterable());
    });

    //when
    validationEngineService.validatePassword(USER_ID_VALUE, password, requestHeaders, checkingHandler);
  }

  @Test
  public void shouldNotFailWhenHaveRepeatingSymbols(TestContext testContext) {
    // given
    String password = "p@ssw0rD";
    mockRegistryServiceResponse(JsonObject.mapFrom(regExpRuleCollection));

    //expect
    Handler<AsyncResult<JsonObject>> checkingHandler = testContext.asyncAssertSuccess(response -> {
      String validationResult = response.getString(RESPONSE_VALIDATION_RESULT_KEY);
      assertThat(validationResult, Matchers.is(VALIDATION_VALID_RESULT));
      JsonArray errorMessages = response.getJsonArray(RESPONSE_ERROR_MESSAGES_KEY);
      assertThat(errorMessages, Matchers.emptyIterable());
    });

    //when
    validationEngineService.validatePassword(USER_ID_VALUE, password, requestHeaders, checkingHandler);
  }

  @Test
  public void shouldNotFailWhenPasswordContainsSingleWhitespaces(TestContext testContext) {
    //given
    String password = " P@ss w0rd ";
    mockRegistryServiceResponse(JsonObject.mapFrom(regExpRuleCollection));

    //expect
    Handler<AsyncResult<JsonObject>> checkingHandler = testContext.asyncAssertSuccess(response -> {
      String validationResult = response.getString(RESPONSE_VALIDATION_RESULT_KEY);
      assertThat(validationResult, Matchers.is(VALIDATION_VALID_RESULT));
      JsonArray errorMessages = response.getJsonArray(RESPONSE_ERROR_MESSAGES_KEY);
      assertThat(errorMessages, Matchers.emptyIterable());
    });

    //when
    validationEngineService.validatePassword(USER_ID_VALUE, password, requestHeaders, checkingHandler);
  }

  @Test
  public void shouldFailWhenPasswordContainsConsecutiveWhitespaces(TestContext testContext) {
    mockRegistryServiceResponse(JsonObject.mapFrom(regExpRuleCollection));

    Handler<AsyncResult<JsonObject>> checkingHandler = testContext.asyncAssertSuccess(response -> {
      String validationResult = response.getString(RESPONSE_VALIDATION_RESULT_KEY);
      assertThat(validationResult, Matchers.is(VALIDATION_INVALID_RESULT));
      JsonArray errorMessages = response.getJsonArray(RESPONSE_ERROR_MESSAGES_KEY);
      assertThat(errorMessages,
        Matchers.contains(REG_CONSECUTIVE_WHITESPACES_RULE.getErrMessageId()));
    });

    Stream.of("  P@ssw0rd", "   P@ssw0rd", "P@ss  w0rd", "P@ss   w0rd", "P@ssw0rd  ", "P@ssw0rd   ")
      .forEach(password -> validationEngineService.validatePassword(USER_ID_VALUE, password,
        requestHeaders, checkingHandler));
  }

  private void mockUserModule(int status, JsonObject response) {
    WireMock.stubFor(WireMock.get("/users?query=id==" + USER_ID_VALUE)
      .willReturn(WireMock.status(status).withBody(response.toString())
      ));
  }

  private void mockRegistryServiceResponse(JsonObject jsonObject) {
    Mockito.doAnswer(new GenericHandlerAnswer<>(Future.succeededFuture(jsonObject), 4))
      .when(validatorRegistryService).getAllTenantRules(ArgumentMatchers.any(), ArgumentMatchers.anyInt(),
      ArgumentMatchers.anyInt(), ArgumentMatchers.any(), ArgumentMatchers.any());
  }
}
