package ai.labs.resources.impl.bots.mongo;

import ai.labs.persistence.IResourceStore;
import ai.labs.persistence.mongo.HistorizedResourceStore;
import ai.labs.persistence.mongo.MongoResourceStorage;
import ai.labs.resources.impl.descriptor.mongo.DocumentDescriptorStore;
import ai.labs.resources.impl.utilities.ResourceUtilities;
import ai.labs.resources.rest.bots.IBotStore;
import ai.labs.resources.rest.bots.model.BotConfiguration;
import ai.labs.resources.rest.documentdescriptor.IDocumentDescriptorStore;
import ai.labs.resources.rest.documentdescriptor.model.DocumentDescriptor;
import ai.labs.serialization.IDocumentBuilder;
import ai.labs.utilities.RuntimeUtilities;
import com.mongodb.client.MongoDatabase;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;

import javax.inject.Inject;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

/**
 * @author ginccc
 */
@Slf4j
public class BotStore implements IBotStore {
    private final IDocumentDescriptorStore documentDescriptorStore;
    private final BotHistorizedResourceStore botResourceStore;

    @Inject
    public BotStore(MongoDatabase database, IDocumentBuilder documentBuilder, DocumentDescriptorStore documentDescriptorStore) {
        this.documentDescriptorStore = documentDescriptorStore;
        RuntimeUtilities.checkNotNull(database, "database");
        final String collectionName = "bots";
        BotMongoResourceStorage resourceStorage =
                new BotMongoResourceStorage(database, collectionName, documentBuilder, BotConfiguration.class);
        this.botResourceStore = new BotHistorizedResourceStore(resourceStorage);
    }

    @Override
    public IResourceId create(BotConfiguration botConfiguration) throws ResourceStoreException {
        RuntimeUtilities.checkCollectionNoNullElements(botConfiguration.getPackages(), "packages");
        return botResourceStore.create(botConfiguration);
    }

    @Override
    public BotConfiguration read(String id, Integer version) throws ResourceNotFoundException, ResourceStoreException {
        return botResourceStore.read(id, version);
    }

    @Override
    public Integer update(String id, Integer version, BotConfiguration botConfiguration) throws ResourceStoreException, ResourceModifiedException, ResourceNotFoundException {
        RuntimeUtilities.checkCollectionNoNullElements(botConfiguration.getPackages(), "packages");
        return botResourceStore.update(id, version, botConfiguration);
    }

    @Override
    public void delete(String id, Integer version) throws ResourceModifiedException, ResourceNotFoundException {
        botResourceStore.delete(id, version);
    }

    @Override
    public void deleteAllPermanently(String id) {
        botResourceStore.deleteAllPermanently(id);
    }

    @Override
    public IResourceId getCurrentResourceId(String id) throws ResourceNotFoundException {
        return botResourceStore.getCurrentResourceId(id);
    }

    public List<DocumentDescriptor> getBotDescriptorsContainingPackage(String packageId, Integer packageVersion)
            throws ResourceNotFoundException, ResourceStoreException {

        List<DocumentDescriptor> ret = new LinkedList<>();
        List<IResourceId> botIdsContainingPackageUri = botResourceStore.getBotIdsContainingPackage(packageId, packageVersion);
        for (IResourceId botIds : botIdsContainingPackageUri) {
            DocumentDescriptor documentDescriptor = documentDescriptorStore.readDescriptor(botIds.getId(), botIds.getVersion());
            ret.add(documentDescriptor);
        }

        return ret;
    }

    private class BotMongoResourceStorage extends MongoResourceStorage<BotConfiguration> {
        private static final String packageResourceURI = "eddi://ai.labs.package/packagestore/packages/";
        private static final String versionQueryParam = "?version=";

        BotMongoResourceStorage(MongoDatabase database, String collectionName, IDocumentBuilder documentBuilder, Class<BotConfiguration> botConfigurationClass) {
            super(database, collectionName, documentBuilder, botConfigurationClass);
        }

        List<IResourceStore.IResourceId> getBotIdsContainingPackageUri(String packageId, Integer packageVersion)
                throws ResourceNotFoundException {

            String searchedForPackageUri = String.join("",
                    packageResourceURI, packageId, versionQueryParam, String.valueOf(packageVersion));
            Document filter = new Document("packages",
                    new Document("$in", Collections.singletonList(searchedForPackageUri)));

            return ResourceUtilities.getAllConfigsContainingResources(filter,
                    currentCollection, historyCollection, documentDescriptorStore);
        }
    }

    private class BotHistorizedResourceStore extends HistorizedResourceStore<BotConfiguration> {
        private final BotMongoResourceStorage resourceStorage;

        BotHistorizedResourceStore(BotMongoResourceStorage resourceStorage) {
            super(resourceStorage);
            this.resourceStorage = resourceStorage;
        }

        List<IResourceId> getBotIdsContainingPackage(String packageId, Integer packageVersion)
                throws ResourceNotFoundException {
            return resourceStorage.getBotIdsContainingPackageUri(packageId, packageVersion);
        }
    }
}