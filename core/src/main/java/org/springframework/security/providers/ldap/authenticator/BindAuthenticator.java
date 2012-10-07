/* Copyright 2004, 2005, 2006 Acegi Technology Pty Limited
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

package org.springframework.security.providers.ldap.authenticator;

import java.util.Iterator;

import javax.naming.directory.Attributes;
import javax.naming.directory.DirContext;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.ldap.NamingException;
import org.springframework.ldap.core.ContextSource;
import org.springframework.ldap.core.DirContextAdapter;
import org.springframework.ldap.core.DirContextOperations;
import org.springframework.ldap.core.DistinguishedName;
import org.springframework.ldap.core.support.BaseLdapPathContextSource;
import org.springframework.ldap.support.LdapUtils;
import org.springframework.security.Authentication;
import org.springframework.security.BadCredentialsException;
import org.springframework.security.ldap.SpringSecurityContextSource;
import org.springframework.security.providers.UsernamePasswordAuthenticationToken;
import org.springframework.util.Assert;


/**
 * An authenticator which binds as a user.
 *
 * @author Luke Taylor
 * @version $Id$
 *
 * @see AbstractLdapAuthenticator
 */
public class BindAuthenticator extends AbstractLdapAuthenticator {
    //~ Static fields/initializers =====================================================================================

    private static final Log logger = LogFactory.getLog(BindAuthenticator.class);

    //~ Constructors ===================================================================================================

    /**
     * Create an initialized instance using the {@link SpringSecurityContextSource} provided.
     *
     * @param contextSource the SpringSecurityContextSource instance against which bind operations will be
     * performed.
     *
     */
    public BindAuthenticator(SpringSecurityContextSource contextSource) {
        super(contextSource);
    }

    //~ Methods ========================================================================================================

    public DirContextOperations authenticate(Authentication authentication) {
        DirContextOperations user = null;
        Assert.isInstanceOf(UsernamePasswordAuthenticationToken.class, authentication,
                "Can only process UsernamePasswordAuthenticationToken objects");

        String username = authentication.getName();
        String password = (String)authentication.getCredentials();

        // If DN patterns are configured, try authenticating with them directly
        Iterator dns = getUserDns(username).iterator();

        while (dns.hasNext() && user == null) {
            user = bindWithDn((String) dns.next(), username, password);
        }

        // Otherwise use the configured locator to find the user
        // and authenticate with the returned DN.
        if (user == null && getUserSearch() != null) {
            DirContextOperations userFromSearch = getUserSearch().searchForUser(username);
            user = bindWithDn(userFromSearch.getDn().toString(), username, password);
        }

        if (user == null) {
            throw new BadCredentialsException(
                    messages.getMessage("BindAuthenticator.badCredentials", "Bad credentials"));
        }

        return user;
    }

    private DirContextOperations bindWithDn(String userDnStr, String username, String password) {
	BaseLdapPathContextSource ctxSource = (BaseLdapPathContextSource) getContextSource();
	DistinguishedName userDn = new DistinguishedName(userDnStr);
	DistinguishedName fullDn = new DistinguishedName(userDn);
	fullDn.prepend(ctxSource.getBaseLdapPath());
	BindWithSpecificDnContextSource specificDnContextSource = new BindWithSpecificDnContextSource(
		(SpringSecurityContextSource) getContextSource(), fullDn,
		password);
	logger.debug("Attemptimg to bind as " + fullDn);
	DirContext ctx = null;
	try {
	    ctx = specificDnContextSource.getReadOnlyContext();

	    Attributes attrs = ctx.getAttributes(userDn, getUserAttributes());

	    DirContextAdapter result = new DirContextAdapter(attrs, userDn, ctxSource.getBaseLdapPath());

	    return result;
	} catch (NamingException e) {
	    // This will be thrown if an invalid user name is used and the method may
	    // be called multiple times to try different names, so we trap the exception
	    // unless a subclass wishes to implement more specialized behaviour.
	    if ((e  instanceof org.springframework.ldap.AuthenticationException)
		    || (e instanceof org.springframework.ldap.OperationNotSupportedException)) {
		handleBindException(userDnStr, username, e);
	    } else {
		throw e;
	    }
	} catch (javax.naming.NamingException e) {
	    throw LdapUtils.convertLdapException(e);
	} finally {
	    LdapUtils.closeContext(ctx);
	}

	return null;

    }

    /**
     * Allows subclasses to inspect the exception thrown by an attempt to bind with a particular DN.
     * The default implementation just reports the failure to the debug log.
     */
    protected void handleBindException(String userDn, String username, Throwable cause) {
        if (logger.isDebugEnabled()) {
            logger.debug("Failed to bind as " + userDn + ": " + cause);
        }
    }

    private class BindWithSpecificDnContextSource implements ContextSource {
        private SpringSecurityContextSource ctxFactory;
        private DistinguishedName userDn;
        private String password;

        public BindWithSpecificDnContextSource(SpringSecurityContextSource ctxFactory, DistinguishedName userDn, String password) {
            this.ctxFactory = ctxFactory;
            this.userDn = userDn;
            this.password = password;
        }

        public DirContext getReadOnlyContext() throws DataAccessException {
            return ctxFactory.getReadWriteContext(userDn.toString(), password);
        }

        public DirContext getReadWriteContext() throws DataAccessException {
            return getReadOnlyContext();
        }
    }

}