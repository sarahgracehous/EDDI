package ai.labs.parser;

import ai.labs.expressions.Expression;
import ai.labs.expressions.utilities.IExpressionProvider;
import ai.labs.lifecycle.ILifecycleTask;
import ai.labs.lifecycle.IllegalExtensionConfigurationException;
import ai.labs.lifecycle.UnrecognizedExtensionException;
import ai.labs.memory.Data;
import ai.labs.memory.IConversationMemory;
import ai.labs.memory.IData;
import ai.labs.output.model.QuickReply;
import ai.labs.parser.extensions.corrections.ICorrection;
import ai.labs.parser.extensions.corrections.providers.ICorrectionProvider;
import ai.labs.parser.extensions.dictionaries.IDictionary;
import ai.labs.parser.extensions.dictionaries.providers.IDictionaryProvider;
import ai.labs.parser.extensions.normalizers.INormalizer;
import ai.labs.parser.extensions.normalizers.providers.INormalizerProvider;
import ai.labs.parser.internal.InputParser;
import ai.labs.parser.internal.matches.RawSolution;
import ai.labs.parser.rest.model.Solution;
import ai.labs.resources.rest.extensions.model.ExtensionDescriptor;
import ai.labs.utilities.RuntimeUtilities;
import ai.labs.utilities.StringUtilities;
import lombok.extern.slf4j.Slf4j;

import javax.inject.Inject;
import javax.inject.Provider;
import java.net.URI;
import java.util.*;
import java.util.stream.Collectors;

import static ai.labs.parser.DictionaryUtilities.convertQuickReplies;
import static ai.labs.parser.DictionaryUtilities.extractExpressions;
import static ai.labs.resources.rest.extensions.model.ExtensionDescriptor.ConfigValue;
import static ai.labs.resources.rest.extensions.model.ExtensionDescriptor.FieldType.BOOLEAN;

/**
 * @author ginccc
 */
@Slf4j
public class InputParserTask implements ILifecycleTask {
    public static final String ID = "ai.labs.parser";
    private static final String CONFIG_APPEND_EXPRESSIONS = "appendExpressions";
    private static final String CONFIG_INCLUDE_UNUSED = "includeUnused";
    private static final String CONFIG_INCLUDE_UNKNOWN = "includeUnknown";
    private static final String EXTENSION_NAME_NORMALIZER = "normalizer";
    private static final String EXTENSION_NAME_DICTIONARIES = "dictionaries";
    private static final String EXTENSION_NAME_CORRECTIONS = "corrections";

    private IInputParser sentenceParser;
    private List<INormalizer> normalizers;
    private List<IDictionary> dictionaries;
    private List<ICorrection> corrections;

    private static final String KEY_INPUT = "input";
    private static final String KEY_INPUT_NORMALIZED = KEY_INPUT + ":normalized";
    private static final String KEY_EXPRESSIONS_PARSED = "expressions:parsed";
    private static final String KEY_INTENT = "intent";
    private static final String KEY_TYPE = "type";
    private static final String KEY_CONFIG = "config";

    private IExpressionProvider expressionProvider;
    private final Map<String, Provider<INormalizerProvider>> normalizerProviders;
    private final Map<String, Provider<IDictionaryProvider>> dictionaryProviders;
    private final Map<String, Provider<ICorrectionProvider>> correctionProviders;
    private boolean appendExpressions = true;
    private boolean includeUnused = true;
    private boolean includeUnknown = true;

    @Inject
    public InputParserTask(IExpressionProvider expressionProvider,
                           Map<String, Provider<INormalizerProvider>> normalizerProviders,
                           Map<String, Provider<IDictionaryProvider>> dictionaryProviders,
                           Map<String, Provider<ICorrectionProvider>> correctionProviders) {
        this.expressionProvider = expressionProvider;
        this.normalizerProviders = normalizerProviders;
        this.dictionaryProviders = dictionaryProviders;
        this.correctionProviders = correctionProviders;
    }

    @Override
    public String getId() {
        return sentenceParser.getClass().toString();
    }

    @Override
    public Object getComponent() {
        return sentenceParser;
    }

    @Override
    public void init() {
        this.sentenceParser = new InputParser(normalizers, dictionaries, corrections);
    }

    @Override
    public void executeTask(IConversationMemory memory) {
        //parse user input to meanings
        final IData<String> inputData = memory.getCurrentStep().getLatestData(KEY_INPUT);
        if (inputData == null) {
            return;
        }

        List<IDictionary> temporaryDictionaries = prepareTemporaryDictionaries(memory);
        List<RawSolution> parsedSolutions;
        try {
            String userInput = inputData.getResult();
            String normalizedUserInput = sentenceParser.normalize(userInput);
            storeNormalizedResultInMemory(memory, normalizedUserInput);
            parsedSolutions = sentenceParser.parse(normalizedUserInput, temporaryDictionaries);
        } catch (InterruptedException e) {
            log.warn(e.getLocalizedMessage(), e);
            return;
        }

        storeResultInMemory(memory, parsedSolutions);
    }

    private List<IDictionary> prepareTemporaryDictionaries(IConversationMemory memory) {
        IConversationMemory.IConversationStepStack previousSteps = memory.getPreviousSteps();
        List<IDictionary> temporaryDictionaries = Collections.emptyList();
        if (previousSteps.size() > 0) {
            List<IData<List<Map<String, String>>>> data = previousSteps.get(0).getAllData("quickReplies");
            if (data != null) {
                List<QuickReply> quickReplies = extractQuickReplies(data);
                temporaryDictionaries = convertQuickReplies(quickReplies, expressionProvider);
            }
        }
        return temporaryDictionaries;
    }

    private List<QuickReply> extractQuickReplies(List<IData<List<Map<String, String>>>> quickReplyDataList) {
        List<QuickReply> ret = new LinkedList<>();
        quickReplyDataList.stream().
                filter(Objects::nonNull).
                filter(IData::isPublic).
                forEach((quickReplyData) -> {
                    List<Map<String, String>> resultList = quickReplyData.getResult();
                    ret.addAll(resultList.stream().
                            map((resultMap) -> new QuickReply(resultMap.get("value"),
                                    resultMap.get("expressions"), Boolean.parseBoolean(resultMap.get("isDefault")))).
                            collect(Collectors.toList()));

                });

        return ret;
    }

    private void storeNormalizedResultInMemory(IConversationMemory memory, String normalizedInput) {
        if (!RuntimeUtilities.isNullOrEmpty(normalizedInput)) {
            IData<String> expressionsData = new Data<>(KEY_INPUT_NORMALIZED, normalizedInput);
            memory.getCurrentStep().resetConversationOutput(KEY_INPUT);
            memory.getCurrentStep().storeData(expressionsData);
        }
    }

    private void storeResultInMemory(IConversationMemory memory, List<RawSolution> parsedSolutions) {
        if (!parsedSolutions.isEmpty()) {
            Solution solution = extractExpressions(parsedSolutions, includeUnused, includeUnknown).get(0);

            String expressions = solution.getExpressions();
            if (appendExpressions && !expressions.isEmpty()) {
                IData<String> latestExpressions = memory.getCurrentStep().getLatestData(KEY_EXPRESSIONS_PARSED);
                if (latestExpressions != null) {
                    expressions = StringUtilities.joinStrings(", ", latestExpressions.getResult(), expressions);
                }

                IData<String> expressionsData = new Data<>(KEY_EXPRESSIONS_PARSED, expressions);
                memory.getCurrentStep().storeData(expressionsData);

                List<String> intents = expressionProvider.parseExpressions(expressions).stream().
                        map(Expression::getExpressionName).
                        collect(Collectors.toList());
                Data<List<String>> intentData = new Data<>(KEY_INTENT, intents);
                memory.getCurrentStep().storeData(intentData);
            }
        }
    }

    @Override
    public void configure(Map<String, Object> configuration) {
        Object appendExpressions = configuration.get(CONFIG_APPEND_EXPRESSIONS);
        if (!RuntimeUtilities.isNullOrEmpty(appendExpressions)) {
            this.appendExpressions = Boolean.parseBoolean(appendExpressions.toString());
        }

        Object includeUnused = configuration.get(CONFIG_INCLUDE_UNUSED);
        if (!RuntimeUtilities.isNullOrEmpty(includeUnused)) {
            this.includeUnused = Boolean.parseBoolean(includeUnused.toString());
        }

        Object includeUnknown = configuration.get(CONFIG_INCLUDE_UNKNOWN);
        if (!RuntimeUtilities.isNullOrEmpty(includeUnknown)) {
            this.includeUnknown = Boolean.parseBoolean(includeUnknown.toString());
        }
    }

    @Override
    public void setExtensions(Map<String, Object> extensions) throws
            UnrecognizedExtensionException, IllegalExtensionConfigurationException {

        List<Map<String, Object>> normalizerList = castToListofMaps(extensions, EXTENSION_NAME_NORMALIZER);
        normalizers = new LinkedList<>();
        if (normalizerList != null) {
            convertNormalizers(normalizerList);
        }

        List<Map<String, Object>> dictionariesList = castToListofMaps(extensions, EXTENSION_NAME_DICTIONARIES);
        dictionaries = new LinkedList<>();
        if (dictionariesList != null) {
            convertDictionaries(dictionariesList);
        }

        corrections = new LinkedList<>();
        List<Map<String, Object>> correctionsList = castToListofMaps(extensions, EXTENSION_NAME_CORRECTIONS);
        if (correctionsList != null) {
            convertCorrections(correctionsList);
        }
    }

    @Override
    public ExtensionDescriptor getExtensionDescriptor() {
        ExtensionDescriptor extensionDescriptor = new ExtensionDescriptor(ID);
        extensionDescriptor.setDisplayName("Input Parser");

        normalizerProviders.keySet().forEach(type -> {
            ExtensionDescriptor normalizerDescriptor = new ExtensionDescriptor(type);
            Provider<INormalizerProvider> normalizerProvider = normalizerProviders.get(type);
            INormalizerProvider provider = normalizerProvider.get();
            normalizerDescriptor.setDisplayName(provider.getDisplayName());
            normalizerDescriptor.setConfigs(provider.getConfigs());
            extensionDescriptor.addExtension(EXTENSION_NAME_NORMALIZER, normalizerDescriptor);
        });

        dictionaryProviders.keySet().forEach(type -> {
            ExtensionDescriptor dictionaryDescriptor = new ExtensionDescriptor(type);
            Provider<IDictionaryProvider> dictionaryProvider = dictionaryProviders.get(type);
            IDictionaryProvider provider = dictionaryProvider.get();
            dictionaryDescriptor.setDisplayName(provider.getDisplayName());
            dictionaryDescriptor.setConfigs(provider.getConfigs());
            extensionDescriptor.addExtension(EXTENSION_NAME_DICTIONARIES, dictionaryDescriptor);
        });

        correctionProviders.keySet().forEach(type -> {
            ExtensionDescriptor correctionsDescriptor = new ExtensionDescriptor(type);
            Provider<ICorrectionProvider> correctionProvider = correctionProviders.get(type);
            ICorrectionProvider provider = correctionProvider.get();
            correctionsDescriptor.setDisplayName(provider.getDisplayName());
            correctionsDescriptor.setConfigs(provider.getConfigs());
            extensionDescriptor.addExtension(EXTENSION_NAME_CORRECTIONS, correctionsDescriptor);
        });

        Map<String, ConfigValue> extensionConfigs = new HashMap<>();
        extensionConfigs.put(CONFIG_APPEND_EXPRESSIONS, new ConfigValue("Append Expressions", BOOLEAN, true, true));
        extensionConfigs.put(CONFIG_INCLUDE_UNUSED, new ConfigValue("Include Unused Expressions", BOOLEAN, true, true));
        extensionConfigs.put(CONFIG_INCLUDE_UNKNOWN, new ConfigValue("Include Unknown Expressions", BOOLEAN, true, true));
        extensionDescriptor.setConfigs(extensionConfigs);

        return extensionDescriptor;
    }

    private void convertNormalizers(List<Map<String, Object>> normalizerList)
            throws UnrecognizedExtensionException, IllegalExtensionConfigurationException {
        for (Map<String, Object> normalizerMap : normalizerList) {
            String normalizerType = getResourceType(normalizerMap);
            Provider<INormalizerProvider> normalizerProvider = normalizerProviders.get(normalizerType);
            if (normalizerProvider != null) {
                INormalizerProvider normalizer = normalizerProvider.get();
                Object configObject = normalizerMap.get(KEY_CONFIG);
                if (configObject instanceof Map) {
                    Map<String, Object> config = castToMap(configObject);
                    normalizer.setConfig(config);
                }
                normalizers.add(normalizer.provide());
            } else {
                String message = "Normalizer type could not be recognized by Parser [type=%s]";
                message = String.format(message, normalizerType);
                throw new UnrecognizedExtensionException(message);
            }
        }
    }

    private void convertDictionaries(List<Map<String, Object>> dictionariesList)
            throws UnrecognizedExtensionException, IllegalExtensionConfigurationException {
        for (Map<String, Object> dictionaryMap : dictionariesList) {
            String dictionaryType = getResourceType(dictionaryMap);
            Provider<IDictionaryProvider> dictionaryProvider = dictionaryProviders.get(dictionaryType);
            if (dictionaryProvider != null) {
                IDictionaryProvider dictionary = dictionaryProvider.get();
                Object configObject = dictionaryMap.get(KEY_CONFIG);
                if (configObject instanceof Map) {
                    Map<String, Object> config = castToMap(configObject);
                    dictionary.setConfig(config);
                }
                dictionaries.add(dictionary.provide());
            } else {
                String message = "Dictionary type could not be recognized by Parser [type=%s]";
                message = String.format(message, dictionaryType);
                throw new UnrecognizedExtensionException(message);
            }
        }
    }

    private void convertCorrections(List<Map<String, Object>> correctionList)
            throws UnrecognizedExtensionException, IllegalExtensionConfigurationException {
        for (Map<String, Object> correctionMap : correctionList) {
            String correctionType = getResourceType(correctionMap);
            Provider<ICorrectionProvider> correctionProviderCreator = correctionProviders.get(correctionType);
            if (correctionProviderCreator != null) {
                ICorrectionProvider correctionProvider = correctionProviderCreator.get();
                Object configObject = correctionMap.get(KEY_CONFIG);
                if (configObject instanceof Map) {
                    Map<String, Object> config = castToMap(configObject);
                    correctionProvider.setConfig(config);
                }
                ICorrection correction = correctionProvider.provide();
                correction.init(dictionaries);
                corrections.add(correction);
            } else {
                String message = "Correction type could not be recognized by Parser [type=%s]";
                message = String.format(message, correctionType);
                throw new UnrecognizedExtensionException(message);
            }
        }
    }

    private static String getResourceType(Map<String, Object> resourceMap) {
        URI normalizerUri = URI.create(resourceMap.get(KEY_TYPE).toString());
        return normalizerUri.getHost();
    }

    private static List<Map<String, Object>> castToListofMaps(Map<String, Object> extensions, String key) {
        return (List<Map<String, Object>>) extensions.get(key);
    }

    private static Map<String, Object> castToMap(Object configObject) {
        return (Map<String, Object>) configObject;
    }
}
