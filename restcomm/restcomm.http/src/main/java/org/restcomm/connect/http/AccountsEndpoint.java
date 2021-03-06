/*
 * TeleStax, Open Source Cloud Communications
 * Copyright 2011-2014, Telestax Inc and individual contributors
 * by the @authors tag.
 *
 * This program is free software: you can redistribute it and/or modify
 * under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation; either version 3 of
 * the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>
 *
 */
package org.restcomm.connect.http;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.sun.jersey.core.util.MultivaluedMapImpl;
import com.thoughtworks.xstream.XStream;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import javax.annotation.PostConstruct;
import javax.servlet.ServletContext;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.core.MediaType;

import static javax.ws.rs.core.MediaType.*;

import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.Response;

import static javax.ws.rs.core.Response.*;
import static javax.ws.rs.core.Response.Status.*;

import org.apache.commons.configuration.Configuration;
import org.apache.shiro.crypto.hash.Md5Hash;
import org.joda.time.DateTime;
import org.restcomm.connect.dao.ClientsDao;
import org.restcomm.connect.dao.DaoManager;
import org.restcomm.connect.dao.entities.Account;
import org.restcomm.connect.dao.entities.AccountList;
import org.restcomm.connect.dao.entities.Client;
import org.restcomm.connect.dao.entities.RestCommResponse;
import org.restcomm.connect.dao.entities.Sid;
import org.restcomm.connect.http.converter.AccountConverter;
import org.restcomm.connect.http.converter.AccountListConverter;
import org.restcomm.connect.http.converter.RestCommResponseConverter;
import org.restcomm.connect.http.exceptions.AuthorizationException;
import org.restcomm.connect.http.exceptions.InsufficientPermission;
import org.restcomm.connect.commons.util.StringUtils;

/**
 * @author quintana.thomas@gmail.com (Thomas Quintana)
 */
public class AccountsEndpoint extends SecuredEndpoint {
    protected Configuration configuration;
    protected Gson gson;
    protected XStream xstream;
    protected ClientsDao clientDao;

    public AccountsEndpoint() {
        super();
    }

    // used for testing
    public AccountsEndpoint(ServletContext context, HttpServletRequest request) {
        super(context,request);
    }

    @PostConstruct
    void init() {
        configuration = (Configuration) context.getAttribute(Configuration.class.getName());
        configuration = configuration.subset("runtime-settings");
        super.init(configuration);
        clientDao = ((DaoManager) context.getAttribute(DaoManager.class.getName())).getClientsDao();
        final AccountConverter converter = new AccountConverter(configuration);
        final GsonBuilder builder = new GsonBuilder();
        builder.registerTypeAdapter(Account.class, converter);
        builder.setPrettyPrinting();
        gson = builder.create();
        xstream = new XStream();
        xstream.alias("RestcommResponse", RestCommResponse.class);
        xstream.registerConverter(converter);
        xstream.registerConverter(new AccountListConverter(configuration));
        xstream.registerConverter(new RestCommResponseConverter(configuration));
        // Make sure there is an authenticated account present when this endpoint is used
        checkAuthenticatedAccount();
    }

    private Account createFrom(final Sid accountSid, final MultivaluedMap<String, String> data) {
        validate(data);

        final DateTime now = DateTime.now();
        final String emailAddress = (data.getFirst("EmailAddress")).toLowerCase();

        // Issue 108: https://bitbucket.org/telestax/telscale-restcomm/issue/108/account-sid-could-be-a-hash-of-the
        final Sid sid = Sid.generate(Sid.Type.ACCOUNT, emailAddress);

        String friendlyName = emailAddress;
        if (data.containsKey("FriendlyName")) {
            friendlyName = data.getFirst("FriendlyName");
        }
        final Account.Type type = Account.Type.FULL;
        Account.Status status = Account.Status.ACTIVE;
        if (data.containsKey("Status")) {
            status = Account.Status.valueOf(data.getFirst("Status"));
        }
        final String password = data.getFirst("Password");
        final String authToken = new Md5Hash(password).toString();
        final String role = data.getFirst("Role");
        String rootUri = configuration.getString("root-uri");
        rootUri = StringUtils.addSuffixIfNotPresent(rootUri, "/");
        final StringBuilder buffer = new StringBuilder();
        buffer.append(rootUri).append(getApiVersion(null)).append("/Accounts/").append(sid.toString());
        final URI uri = URI.create(buffer.toString());
        return new Account(sid, now, now, emailAddress, friendlyName, accountSid, type, status, authToken, role, uri);
    }

    protected Response getAccount(final String accountSid, final MediaType responseType) {
        //First check if the account has the required permissions in general, this way we can fail fast and avoid expensive DAO operations
        Account account = null;
        checkPermission("RestComm:Read:Accounts");
        if (Sid.pattern.matcher(accountSid).matches()) {
            try {
                account = accountsDao.getAccount(new Sid(accountSid));
            } catch (Exception e) {
                return status(NOT_FOUND).build();
            }
        } else {
            try {
                account = accountsDao.getAccount(accountSid);
            } catch (Exception e) {
                return status(NOT_FOUND).build();
            }
        }

        secure(account, "RestComm:Read:Accounts", SecuredType.SECURED_ACCOUNT );

        if (account == null) {
            return status(NOT_FOUND).build();
        } else {
            if (APPLICATION_XML_TYPE == responseType) {
                final RestCommResponse response = new RestCommResponse(account);
                return ok(xstream.toXML(response), APPLICATION_XML).build();
            } else if (APPLICATION_JSON_TYPE == responseType) {
                return ok(gson.toJson(account), APPLICATION_JSON).build();
            } else {
                return null;
            }
        }
    }

    protected Response deleteAccount(final String operatedSid) {
        //First check if the account has the required permissions in general, this way we can fail fast and avoid expensive DAO operations
        checkPermission("RestComm:Delete:Accounts");
        // what if effectiveAccount is null ?? - no need to check since we checkAuthenticatedAccount() in AccountsEndoint.init()
        final Sid accountSid = userIdentityContext.getEffectiveAccount().getSid();
        final Sid sidToBeRemoved = new Sid(operatedSid);

        Account removedAccount = accountsDao.getAccount(sidToBeRemoved);
        secure(removedAccount, "RestComm:Delete:Accounts", SecuredType.SECURED_ACCOUNT);
        // Prevent removal of Administrator account
        if (operatedSid.equalsIgnoreCase(accountSid.toString()))
            return status(BAD_REQUEST).build();

        if (accountsDao.getAccount(sidToBeRemoved) == null)
            return status(NOT_FOUND).build();

        accountsDao.removeAccount(sidToBeRemoved);

        // Remove its SIP client account
        clientDao.removeClients(sidToBeRemoved);

        return ok().build();
    }

    protected Response getAccounts(final MediaType responseType) {
        //First check if the account has the required permissions in general, this way we can fail fast and avoid expensive DAO operations
        checkPermission("RestComm:Read:Accounts");
        final Account account = userIdentityContext.getEffectiveAccount();
        if (account == null) {
            return status(NOT_FOUND).build();
        } else {
            final List<Account> accounts = new ArrayList<Account>();
            accounts.add(account);
            accounts.addAll(accountsDao.getAccounts(account.getSid()));
            if (APPLICATION_XML_TYPE == responseType) {
                final RestCommResponse response = new RestCommResponse(new AccountList(accounts));
                return ok(xstream.toXML(response), APPLICATION_XML).build();
            } else if (APPLICATION_JSON_TYPE == responseType) {
                return ok(gson.toJson(accounts), APPLICATION_JSON).build();
            } else {
                return null;
            }
        }
    }

    protected Response putAccount(final MultivaluedMap<String, String> data, final MediaType responseType) {
        //First check if the account has the required permissions in general, this way we can fail fast and avoid expensive DAO operations
        checkPermission("RestComm:Create:Accounts");
        // what if effectiveAccount is null ?? - no need to check since we checkAuthenticatedAccount() in AccountsEndoint.init()
        final Sid sid = userIdentityContext.getEffectiveAccount().getSid();
        Account account = null;
        try {
            account = createFrom(sid, data);
        } catch (final NullPointerException exception) {
            return status(BAD_REQUEST).entity(exception.getMessage()).build();
        }

        // If Account already exists don't add it again
        /*
            Account creation rules:
            - either be Administrator or have the following permission: RestComm:Create:Accounts
            - only Administrators can choose a role for newly created accounts. Normal users will create accounts with the same role as their own.
         */
        if (accountsDao.getAccount(account.getSid()) == null && !account.getEmailAddress().equalsIgnoreCase("administrator@company.com")) {
            final Account parent = accountsDao.getAccount(sid);
            if (parent.getStatus().equals(Account.Status.ACTIVE) && isSecuredByPermission("RestComm:Create:Accounts")) {
                if (!hasAccountRole(getAdministratorRole()) || !data.containsKey("Role")) {
                    account = account.setRole(parent.getRole());
                }
                accountsDao.addAccount(account);

                // Create default SIP client data
                MultivaluedMap<String, String> clientData = new MultivaluedMapImpl();
                String username = data.getFirst("EmailAddress").split("@")[0];
                clientData.add("Login", username);
                clientData.add("Password", data.getFirst("Password"));
                clientData.add("FriendlyName", account.getFriendlyName());
                clientData.add("AccountSid", account.getSid().toString());
                Client client = clientDao.getClient(clientData.getFirst("Login"));
                if (client == null) {
                    client = createClientFrom(account.getSid(), clientData);
                    clientDao.addClient(client);
                }
            } else {
                throw new InsufficientPermission();
            }
        } else {
            return status(CONFLICT).entity("The email address used for the new account is already in use.").build();
        }

        if (APPLICATION_JSON_TYPE == responseType) {
            return ok(gson.toJson(account), APPLICATION_JSON).build();
        } else if (APPLICATION_XML_TYPE == responseType) {
            final RestCommResponse response = new RestCommResponse(account);
            return ok(xstream.toXML(response), APPLICATION_XML).build();
        } else {
            return null;
        }
    }

    private Client createClientFrom(final Sid accountSid, final MultivaluedMap<String, String> data) {
        final Client.Builder builder = Client.builder();
        final Sid sid = Sid.generate(Sid.Type.CLIENT);

        // TODO: need to encrypt this password because it's same with Account
        // password.
        // Don't implement now. Opened another issue for it.
        // String password = new Md5Hash(data.getFirst("Password")).toString();
        String password = data.getFirst("Password");

        builder.setSid(sid);
        builder.setAccountSid(accountSid);
        builder.setApiVersion(getApiVersion(data));
        builder.setLogin(data.getFirst("Login"));
        builder.setPassword(password);
        builder.setFriendlyName(data.getFirst("FriendlyName"));
        builder.setStatus(Client.ENABLED);
        String rootUri = configuration.getString("root-uri");
        rootUri = StringUtils.addSuffixIfNotPresent(rootUri, "/");
        final StringBuilder buffer = new StringBuilder();
        buffer.append(rootUri).append(getApiVersion(data)).append("/Accounts/").append(accountSid.toString())
                .append("/Clients/").append(sid.toString());
        builder.setUri(URI.create(buffer.toString()));
        return builder.build();
    }

    private Account update(final Account account, final MultivaluedMap<String, String> data) {
        Account result = account;
        boolean isPasswordReset = false;
        try {
            if (data.containsKey("FriendlyName")) {
                result = result.setFriendlyName(data.getFirst("FriendlyName"));
            }
            if (data.containsKey("Password")) {
                // if this is a reset-password operation, we also need to set the account status to active
                if (account.getStatus() == Account.Status.UNINITIALIZED)
                    isPasswordReset = true;

                final String hash = new Md5Hash(data.getFirst("Password")).toString();
                result = result.setAuthToken(hash);
            }
            if (data.containsKey("Auth_Token")) {
                result = result.setAuthToken(data.getFirst("Auth_Token"));
                // if this is a reset-password operation, we also need to set the account status to active
                if (account.getStatus() == Account.Status.UNINITIALIZED)
                    isPasswordReset = true;
            }
            if (data.containsKey("Status")) {
                result = result.setStatus(Account.Status.getValueOf(data.getFirst("Status").toLowerCase()));
            } else {
                // if this is a password reset operation we need to active the account too (in case there is no explicity Status passed of course)
                if (isPasswordReset)
                    result = result.setStatus(Account.Status.ACTIVE);
            }
            if (data.containsKey("Role")) {
                Account operatingAccount = userIdentityContext.getEffectiveAccount();
                // Only allow role change for administrators. Multitenancy checks will take care of restricting the modification scope to sub-accounts.
                if (userIdentityContext.getEffectiveAccountRoles().contains(getAdministratorRole())) {
                    result = result.setRole(data.getFirst("Role"));
                } else
                    throw new AuthorizationException();
            }
        } catch (AuthorizationException e) {
            // authorization exceptions should reach outer layers and result in 403
            throw e;
        } catch (Exception e) {
            if (logger.isInfoEnabled()) {
                logger.info("Exception during Account update: "+e.getStackTrace());
            }
        }
        return result;
    }

    protected Response updateAccount(final String identifier, final MultivaluedMap<String, String> data,
            final MediaType responseType) {
        // First check if the account has the required permissions in general, this way we can fail fast and avoid expensive DAO
        // operations
        checkPermission("RestComm:Modify:Accounts");
        Sid sid = null;
        Account account = null;
        try {
            sid = new Sid(identifier);
            account = accountsDao.getAccount(sid);
        } catch (Exception e) {
            if (logger.isDebugEnabled()) {
                logger.debug("At update account, exception trying to get SID. Seems we have email as identifier");
            }
        }
        if (account == null) {
            if (logger.isDebugEnabled()) {
                logger.debug("At update account, trying to get account using email as identifier");
            }
            account = accountsDao.getAccount(identifier);
        }

        if (account == null) {
            return status(NOT_FOUND).build();
        } else {
            account = update(account, data);

            secure(account, "RestComm:Modify:Accounts", SecuredType.SECURED_ACCOUNT);
            accountsDao.updateAccount(account);

            // Update SIP client of the corresponding Account
            String email = account.getEmailAddress();
            if (email != null && !email.equals("")) {
                String username = email.split("@")[0];
                Client client = clientDao.getClient(username);
                if (client != null) {
                    // TODO: need to encrypt this password because it's
                    // same with Account password.
                    // Don't implement now. Opened another issue for it.
                    if (data.containsKey("Password")) {
                        // Md5Hash(data.getFirst("Password")).toString();
                        String password = data.getFirst("Password");
                        client = client.setPassword(password);
                    }

                    if (data.containsKey("FriendlyName")) {
                        client = client.setFriendlyName(data.getFirst("FriendlyName"));
                    }

                    clientDao.updateClient(client);
                }
            }

            if (APPLICATION_JSON_TYPE == responseType) {
                return ok(gson.toJson(account), APPLICATION_JSON).build();
            } else if (APPLICATION_XML_TYPE == responseType) {
                final RestCommResponse response = new RestCommResponse(account);
                return ok(xstream.toXML(response), APPLICATION_XML).build();
            } else {
                return null;
            }
        }
    }

    private void validate(final MultivaluedMap<String, String> data) throws NullPointerException {
        if (!data.containsKey("EmailAddress")) {
            throw new NullPointerException("Email address can not be null.");
        } else if (!data.containsKey("Password")) {
            throw new NullPointerException("Password can not be null.");
        }
    }

}
