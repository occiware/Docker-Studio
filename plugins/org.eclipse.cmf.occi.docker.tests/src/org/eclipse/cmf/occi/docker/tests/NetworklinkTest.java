/**
 * Copyright (c) 2015-2017 Obeo, Inria
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 	
 * Contributors:
 * - William Piers <william.piers@obeo.fr>
 * - Philippe Merle <philippe.merle@inria.fr>
 * - Faiez Zalila <faiez.zalila@inria.fr>
 */
package org.eclipse.cmf.occi.docker.tests;

import junit.textui.TestRunner;

import org.eclipse.cmf.occi.docker.DockerFactory;
import org.eclipse.cmf.occi.docker.Networklink;

/**
 * <!-- begin-user-doc -->
 * A test case for the model object '<em><b>Networklink</b></em>'.
 * <!-- end-user-doc -->
 * @generated
 */
public class NetworklinkTest extends LinkTest {

	/**
	 * <!-- begin-user-doc -->
	 * <!-- end-user-doc -->
	 * @generated
	 */
	public static void main(String[] args) {
		TestRunner.run(NetworklinkTest.class);
	}

	/**
	 * Constructs a new Networklink test case with the given name.
	 * <!-- begin-user-doc -->
	 * <!-- end-user-doc -->
	 * @generated
	 */
	public NetworklinkTest(String name) {
		super(name);
	}

	/**
	 * Returns the fixture for this Networklink test case.
	 * <!-- begin-user-doc -->
	 * <!-- end-user-doc -->
	 * @generated
	 */
	@Override
	protected Networklink getFixture() {
		return (Networklink)fixture;
	}

	/**
	 * <!-- begin-user-doc -->
	 * <!-- end-user-doc -->
	 * @see junit.framework.TestCase#setUp()
	 * @generated
	 */
	@Override
	protected void setUp() throws Exception {
		setFixture(DockerFactory.eINSTANCE.createNetworklink());
	}

	/**
	 * <!-- begin-user-doc -->
	 * <!-- end-user-doc -->
	 * @see junit.framework.TestCase#tearDown()
	 * @generated
	 */
	@Override
	protected void tearDown() throws Exception {
		setFixture(null);
	}

} //NetworklinkTest
