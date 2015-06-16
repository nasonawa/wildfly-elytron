/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2014 Red Hat, Inc., and individual contributors
 * as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.wildfly.security.auth.login;

import java.io.IOException;
import java.security.NoSuchAlgorithmException;
import java.security.Principal;
import java.security.spec.InvalidKeySpecException;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLSocket;
import javax.security.auth.callback.Callback;
import javax.security.auth.callback.CallbackHandler;
import javax.security.auth.callback.NameCallback;
import javax.security.auth.callback.PasswordCallback;
import javax.security.auth.callback.UnsupportedCallbackException;
import javax.security.sasl.SaslException;
import javax.security.sasl.SaslServer;
import javax.security.sasl.SaslServerFactory;

import org.wildfly.common.Assert;
import org.wildfly.security._private.ElytronMessages;
import org.wildfly.security.auth.callback.AnonymousAuthorizationCallback;
import org.wildfly.security.auth.callback.AuthenticationCompleteCallback;
import org.wildfly.security.auth.callback.CallbackUtil;
import org.wildfly.security.auth.callback.CredentialCallback;
import org.wildfly.security.auth.callback.CredentialParameterCallback;
import org.wildfly.security.auth.callback.CredentialVerifyCallback;
import org.wildfly.security.auth.callback.FastUnsupportedCallbackException;
import org.wildfly.security.auth.callback.PasswordVerifyCallback;
import org.wildfly.security.auth.callback.PeerPrincipalCallback;
import org.wildfly.security.auth.callback.SecurityIdentityCallback;
import org.wildfly.security.auth.callback.SocketAddressCallback;
import org.wildfly.security.auth.spi.AuthorizationIdentity;
import org.wildfly.security.auth.spi.CredentialSupport;
import org.wildfly.security.auth.spi.RealmIdentity;
import org.wildfly.security.auth.spi.RealmUnavailableException;
import org.wildfly.security.auth.spi.SecurityRealm;
import org.wildfly.security.password.Password;
import org.wildfly.security.password.PasswordFactory;
import org.wildfly.security.password.TwoWayPassword;
import org.wildfly.security.password.interfaces.ClearPassword;
import org.wildfly.security.password.spec.ClearPasswordSpec;
import org.wildfly.security.sasl.WildFlySasl;
import org.wildfly.security.sasl.util.AuthenticationCompleteCallbackSaslServerFactory;

/**
 * Server-side authentication context.
 *
 * @author <a href="mailto:david.lloyd@redhat.com">David M. Lloyd</a>
 * @author <a href="mailto:darran.lofthouse@jboss.com">Darran Lofthouse</a>
 */
public final class ServerAuthenticationContext {

    private static final Map<String, String> QUERY_ALL = Collections.singletonMap(WildFlySasl.MECHANISM_QUERY_ALL, "true");

    private final SecurityDomain domain;
    private final AtomicReference<State> stateRef = new AtomicReference<>(INITIAL);

    ServerAuthenticationContext(final SecurityDomain domain) {
        this.domain = domain;
    }

    /**
     * Create a SASL server that will authenticate against this security domain.  The selected mechanism name should
     * be one of the names returned by {@link SecurityDomain#getSaslServerMechanismNames(SaslServerFactory)} for the given factory.
     * If the SASL server failed to be constructed for some reason, a {@code SaslException} is thrown.  Calling this
     * method initiates authentication.
     *
     * @param saslServerFactory the SASL server factory
     * @param serverName the server name, or {@code null} to create an unbound SASL server (if allowed by the mechanism)
     * @param mechanismName the selected mechanism name
     * @param protocol the protocol which is currently in use
     * @return the SASL server
     * @throws SaslException if creating the SASL server failed for some reason
     * @throws IllegalStateException if authentication was already initiated on this context
     */
    public SaslServer createSaslServer(SaslServerFactory saslServerFactory, String serverName, String mechanismName, String protocol) throws SaslException, IllegalStateException {
        Assert.checkNotNullParam("saslServerFactory", saslServerFactory);
        Assert.checkNotNullParam("mechanismName", mechanismName);
        Assert.checkNotNullParam("protocol", protocol);
        State oldState;
        do {
            oldState = stateRef.get();
            if (oldState != INITIAL) {
                throw ElytronMessages.log.alreadyInitiated();
            }
        } while (! stateRef.compareAndSet(INITIAL, IN_PROGRESS));
        return new AuthenticationCompleteCallbackSaslServerFactory(saslServerFactory).createSaslServer(mechanismName, protocol, serverName, QUERY_ALL, createCallbackHandler());
    }

    /**
     * Create a server-side SSL engine that authenticates using this authentication context.  Calling this method
     * initiates authentication.
     *
     * @return the SSL engine
     * @throws IllegalStateException if authentication was already initiated on this context
     */
    public SSLEngine createServerSslEngine() throws IllegalStateException {
        State oldState;
        do {
            oldState = stateRef.get();
            if (oldState != INITIAL) {
                throw ElytronMessages.log.alreadyInitiated();
            }
        } while (! stateRef.compareAndSet(INITIAL, IN_PROGRESS));
        throw new UnsupportedOperationException();
    }

    /**
     * Create a server-side SSL socket that authenticates using this authentication context.  Calling this method
     * initiates authentication.
     *
     * @return the SSL socket
     * @throws IllegalStateException if authentication was already initiated on this context
     */
    public SSLSocket createServerSslSocket() throws IllegalStateException {
        State oldState;
        do {
            oldState = stateRef.get();
            if (oldState != INITIAL) {
                throw ElytronMessages.log.alreadyInitiated();
            }
        } while (! stateRef.compareAndSet(INITIAL, IN_PROGRESS));
        throw new UnsupportedOperationException();
    }

    /**
     * Get the authorized identity result of this authentication.
     *
     * @return the authorized identity
     * @throws IllegalStateException if the authentication is incomplete
     */
    public SecurityIdentity getAuthorizedIdentity() throws IllegalStateException {
        return stateRef.get().getAuthorizedIdentity();
    }

    /**
     * Set the authentication name for this authentication.  Calling this method initiates authentication.
     *
     * @param name the authentication name
     * @throws IllegalArgumentException if the name is syntactically invalid
     * @throws RealmUnavailableException if the realm is not available
     * @throws IllegalStateException if the authentication name was already set
     */
    public void setAuthenticationName(String name) throws IllegalArgumentException, RealmUnavailableException, IllegalStateException {
        Assert.checkNotNullParam("name", name);
        State oldState;
        do {
            oldState = stateRef.get();
            if (oldState != INITIAL) {
                throw ElytronMessages.log.alreadyInitiated();
            }
        } while (! stateRef.compareAndSet(INITIAL, IN_PROGRESS));
        name = domain.getPreRealmRewriter().rewriteName(name);
        String realmName = domain.getRealmMapper().getRealmMapping(name);
        if (realmName == null) {
            realmName = domain.getDefaultRealmName();
        }
        RealmInfo realmInfo = domain.getRealmInfo(realmName);
        name = domain.getPostRealmRewriter().rewriteName(name);
        name = realmInfo.getNameRewriter().rewriteName(name);
        final SecurityRealm securityRealm = realmInfo.getSecurityRealm();
        final RealmIdentity realmIdentity = securityRealm.createRealmIdentity(name);
        boolean ok = false;
        try {
            if (! stateRef.compareAndSet(IN_PROGRESS, new NameAssignedState(realmInfo, realmIdentity))) {
                throw Assert.unreachableCode();
            }
            ok = true;
        } finally {
            if (! ok) realmIdentity.dispose();
        }
    }

    /**
     * Mark this authentication as "failed".  The context cannot be used after this method is called.
     *
     * @throws IllegalStateException if no authentication has been initiated or authentication is already completed
     */
    public void fail() throws IllegalStateException {
        State oldState;
        do {
            oldState = stateRef.get();
            if (oldState != IN_PROGRESS && oldState.getId() != ASSIGNED_ID) {
                throw ElytronMessages.log.noAuthenticationInProgress();
            }
        } while (!stateRef.compareAndSet(oldState, FAILED));
        if (oldState.getId() == ASSIGNED_ID) {
            oldState.getRealmIdentity().dispose();
        }
    }

    /**
     * Mark this authentication as "successful".  The context cannot be used after this method is called, however
     * the authorized identity may thereafter be accessed via the {@link #getAuthorizedIdentity()} method.
     *
     * @throws IllegalStateException if no authentication has been initiated or authentication is already completed
     */
    public void succeed() throws IllegalStateException, RealmUnavailableException {
        State oldState = stateRef.get();
        if (oldState.getId() != ASSIGNED_ID) {
            throw ElytronMessages.log.noAuthenticationInProgress();
        }
        RealmInfo realmInfo = oldState.getRealmInfo();
        final AuthorizationIdentity authorizationIdentity = oldState.getRealmIdentity().getAuthorizationIdentity();
        CompleteState newState = new CompleteState(new SecurityIdentity(domain, realmInfo, authorizationIdentity));
        while (! stateRef.compareAndSet(oldState, newState)) {
            oldState = stateRef.get();
            if (oldState.getId() != ASSIGNED_ID) {
                throw ElytronMessages.log.noAuthenticationInProgress();
            }
        }
        oldState.getRealmIdentity().dispose();
    }

    /**
     * Get the principal associated with the current authentication name.  Only valid during authentication process.
     *
     * @return the principal
     * @throws RealmUnavailableException if the realm is not available
     * @throws IllegalStateException if no authentication has been initiated or authentication is already completed
     */
    public Principal getAuthenticationPrincipal() throws RealmUnavailableException {
        return stateRef.get().getAuthenticationPrincipal();
    }

    /**
     * Determine whether a given credential is definitely supported, possibly supported, or definitely not supported for
     * the current authentication identity.
     *
     * @param credentialType the credential type
     *
     * @return the level of support for this credential type
     *
     * @throws RealmUnavailableException if the realm is not able to handle requests for any reason
     * @throws IllegalStateException if no authentication has been initiated or authentication is already completed
     */
    public CredentialSupport getCredentialSupport(Class<?> credentialType) throws RealmUnavailableException {
        return stateRef.get().getCredentialSupport(credentialType);
    }

    /**
     * Acquire a credential of the given type.
     *
     * @param credentialType the credential type class
     * @param <C> the credential type
     *
     * @return the credential, or {@code null} if the principal has no credential of that type
     *
     * @throws RealmUnavailableException if the realm is not able to handle requests for any reason
     * @throws IllegalStateException if no authentication has been initiated or authentication is already completed
     */
    public <C> C getCredential(Class<C> credentialType) throws RealmUnavailableException {
        return stateRef.get().getCredential(credentialType);
    }

    /**
     * Verify the given credential.
     *
     * @param credential the credential to verify
     *
     * @return {@code true} if verification was successful, {@code false} otherwise
     *
     * @throws RealmUnavailableException if the realm is not able to handle requests for any reason
     * @throws IllegalStateException if no authentication has been initiated or authentication is already completed
     */
    public boolean verifyCredential(Object credential) throws RealmUnavailableException {
        return stateRef.get().verifyCredential(credential);
    }

    CallbackHandler createCallbackHandler() {
        return new CallbackHandler() {

            @Override
            public void handle(final Callback[] callbacks) throws IOException, UnsupportedCallbackException {
                handleOne(callbacks, 0);
            }

            private void handleOne(final Callback[] callbacks, final int idx) throws IOException, UnsupportedCallbackException {
                if (idx == callbacks.length) {
                    return;
                }
                Callback callback = callbacks[idx];
                if (callback instanceof NameCallback) {
                    // login name
                    final String name = ((NameCallback) callback).getName();
                    try {
                        setAuthenticationName(name);
                    } catch (Exception e) {
                        throw new IOException(e);
                    }
                    handleOne(callbacks, idx + 1);
                } else if (callback instanceof PeerPrincipalCallback) {
                    // login name
                    final Principal principal = ((PeerPrincipalCallback) callback).getPrincipal();
                    try {
                        // todo: handle X500 properly
                        setAuthenticationName(principal.getName());
                    } catch (Exception e) {
                        throw new IOException(e);
                    }
                    handleOne(callbacks, idx + 1);
                } else if (callback instanceof PasswordVerifyCallback) {
                    final PasswordVerifyCallback passwordVerifyCallback = (PasswordVerifyCallback) callback;
                    // need a plain password
                    final char[] providedPassword = passwordVerifyCallback.getPassword();
                    if (getCredentialSupport(char[].class).isDefinitelyVerifiable()) {
                        passwordVerifyCallback.setVerified(verifyCredential(providedPassword));
                    } else if (getCredentialSupport(TwoWayPassword.class).isDefinitelyVerifiable()) {
                        try {
                            final PasswordFactory passwordFactory = PasswordFactory.getInstance(ClearPassword.ALGORITHM_CLEAR);
                            final Password password = passwordFactory.generatePassword(new ClearPasswordSpec(providedPassword));
                            passwordVerifyCallback.setVerified(verifyCredential(password));
                        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
                            // try to fall back to another credential type
                            throw new FastUnsupportedCallbackException(callback);
                        }
                    } else {
                        // try to fall back to another credential type
                        throw new FastUnsupportedCallbackException(callback);
                    }
                    handleOne(callbacks, idx + 1);
                } else if (callback instanceof PasswordCallback) {
                    final PasswordCallback passwordCallback = (PasswordCallback) callback;

                    final TwoWayPassword credential = getCredential(TwoWayPassword.class);
                    if (credential == null) {
                        // there's a slight hope that we could get a proper credential callback
                        throw new FastUnsupportedCallbackException(callback);
                    }
                    final ClearPasswordSpec clearPasswordSpec;
                    try {
                        final PasswordFactory passwordFactory = PasswordFactory.getInstance(credential.getAlgorithm());
                        clearPasswordSpec = passwordFactory.getKeySpec(credential, ClearPasswordSpec.class);
                    } catch (InvalidKeySpecException | NoSuchAlgorithmException e) {
                        // try to fall back to another credential type
                        throw new FastUnsupportedCallbackException(callback);
                    }
                    passwordCallback.setPassword(clearPasswordSpec.getEncodedPassword());

                    handleOne(callbacks, idx + 1);
                } else if (callback instanceof CredentialCallback) {
                    final CredentialCallback credentialCallback = (CredentialCallback) callback;
                    for (Class<?> allowedType : credentialCallback.getAllowedTypes()) {
                        if (getCredentialSupport(allowedType).mayBeObtainable()) {
                            final Object credential = getCredential(allowedType);
                            if (credential != null) {
                                credentialCallback.setCredential(credential);
                                break;
                            }
                        }
                    }
                    // otherwise just fall out; some mechanisms will try again with different credentials
                    handleOne(callbacks, idx + 1);
                } else if (callback instanceof CredentialVerifyCallback) {
                    CredentialVerifyCallback credentialVerifyCallback = (CredentialVerifyCallback) callback;

                    Object credential = credentialVerifyCallback.getCredential();
                    if (getCredentialSupport(credential.getClass()).isDefinitelyVerifiable()) {
                        credentialVerifyCallback.setVerified(verifyCredential(credential));
                    }
                } else if (callback instanceof CredentialParameterCallback) {
                    // ignore for now
                    handleOne(callbacks, idx + 1);
                } else if (callback instanceof AnonymousAuthorizationCallback) {
                    ((AnonymousAuthorizationCallback) callback).setAuthorized(domain.isAnonymousAllowed());
                    handleOne(callbacks, idx + 1);
                } else if (callback instanceof AuthenticationCompleteCallback) {
                    if (((AuthenticationCompleteCallback) callback).succeeded()) {
                        succeed();
                    } else {
                        fail();
                    }
                    handleOne(callbacks, idx + 1);
                } else if (callback instanceof SocketAddressCallback) {
                    final SocketAddressCallback socketAddressCallback = (SocketAddressCallback) callback;
                    if (socketAddressCallback.getKind() == SocketAddressCallback.Kind.PEER) {
                        // todo: filter by IP address
                    }
                    handleOne(callbacks, idx + 1);
                } else if (callback instanceof SecurityIdentityCallback) {
                    ((SecurityIdentityCallback) callback).setSecurityIdentity(getAuthorizedIdentity());
                    handleOne(callbacks, idx + 1);
                } else {
                    CallbackUtil.unsupported(callback);
                }
            }
        };
    }

    private static final int INITIAL_ID = 0;
    private static final int IN_PROGRESS_ID = 1;
    private static final int FAILED_ID = 2;
    private static final int ASSIGNED_ID = 3;
    private static final int COMPLETE_ID = 4;

    abstract static class State {
        abstract int getId();

        abstract SecurityIdentity getAuthorizedIdentity();

        abstract Principal getAuthenticationPrincipal() throws RealmUnavailableException;

        abstract CredentialSupport getCredentialSupport(final Class<?> credentialType) throws RealmUnavailableException;

        abstract <C> C getCredential(final Class<C> credentialType) throws RealmUnavailableException;

        abstract boolean verifyCredential(final Object credential) throws RealmUnavailableException;

        abstract RealmInfo getRealmInfo();

        abstract RealmIdentity getRealmIdentity();
    }

    static final class SimpleState extends State {
        private final int id;

        SimpleState(final int id) {
            this.id = id;
        }

        @Override
        public int getId() {
            return id;
        }

        @Override
        SecurityIdentity getAuthorizedIdentity() {
            throw ElytronMessages.log.noSuccessfulAuthentication();
        }

        @Override
        Principal getAuthenticationPrincipal() {
            throw ElytronMessages.log.noAuthenticationInProgress();
        }

        @Override
        CredentialSupport getCredentialSupport(final Class<?> credentialType) {
            throw ElytronMessages.log.noAuthenticationInProgress();
        }

        @Override
        <C> C getCredential(final Class<C> credentialType) throws RealmUnavailableException {
            throw ElytronMessages.log.noAuthenticationInProgress();
        }

        @Override
        boolean verifyCredential(final Object credential) throws RealmUnavailableException {
            throw ElytronMessages.log.noAuthenticationInProgress();
        }

        @Override
        RealmInfo getRealmInfo() {
            throw ElytronMessages.log.noAuthenticationInProgress();
        }

        @Override
        RealmIdentity getRealmIdentity() {
            throw ElytronMessages.log.noAuthenticationInProgress();
        }
    }

    static final class CompleteState extends State {
        private final SecurityIdentity identity;

        public CompleteState(final SecurityIdentity identity) {
            this.identity = identity;
        }

        @Override
        int getId() {
            return COMPLETE_ID;
        }

        @Override
        SecurityIdentity getAuthorizedIdentity() {
            return identity;
        }

        @Override
        Principal getAuthenticationPrincipal() {
            throw ElytronMessages.log.noAuthenticationInProgress();
        }

        @Override
        CredentialSupport getCredentialSupport(final Class<?> credentialType) {
            throw ElytronMessages.log.noAuthenticationInProgress();
        }

        @Override
        <C> C getCredential(final Class<C> credentialType) throws RealmUnavailableException {
            throw ElytronMessages.log.noAuthenticationInProgress();
        }

        @Override
        boolean verifyCredential(final Object credential) throws RealmUnavailableException {
            throw ElytronMessages.log.noAuthenticationInProgress();
        }

        @Override
        RealmInfo getRealmInfo() {
            throw ElytronMessages.log.noAuthenticationInProgress();
        }

        @Override
        RealmIdentity getRealmIdentity() {
            throw ElytronMessages.log.noAuthenticationInProgress();
        }
    }

    static final class NameAssignedState extends State {
        private final RealmInfo realmInfo;
        private final RealmIdentity realmIdentity;

        NameAssignedState(final RealmInfo realmInfo, final RealmIdentity realmIdentity) {
            this.realmInfo = realmInfo;
            this.realmIdentity = realmIdentity;
        }

        @Override
        int getId() {
            return ASSIGNED_ID;
        }

        @Override
        SecurityIdentity getAuthorizedIdentity() {
            throw ElytronMessages.log.noSuccessfulAuthentication();
        }

        @Override
        Principal getAuthenticationPrincipal() throws RealmUnavailableException {
            return realmIdentity.getPrincipal();
        }

        @Override
        CredentialSupport getCredentialSupport(final Class<?> credentialType) throws RealmUnavailableException {
            return realmIdentity.getCredentialSupport(credentialType);
        }

        @Override
        <C> C getCredential(final Class<C> credentialType) throws RealmUnavailableException {
            return realmIdentity.getCredential(credentialType);
        }

        @Override
        boolean verifyCredential(final Object credential) throws RealmUnavailableException {
            return realmIdentity.verifyCredential(credential);
        }

        @Override
        RealmInfo getRealmInfo() {
            return realmInfo;
        }

        @Override
        RealmIdentity getRealmIdentity() {
            return realmIdentity;
        }
    }

    private static final SimpleState INITIAL = new SimpleState(INITIAL_ID);
    private static final SimpleState IN_PROGRESS = new SimpleState(IN_PROGRESS_ID);
    private static final SimpleState FAILED = new SimpleState(FAILED_ID);
}