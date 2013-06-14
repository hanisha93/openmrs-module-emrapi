/*
 * The contents of this file are subject to the OpenMRS Public License
 * Version 1.0 (the "License"); you may not use this file except in
 * compliance with the License. You may obtain a copy of the License at
 * http://license.openmrs.org
 *
 * Software distributed under the License is distributed on an "AS IS"
 * basis, WITHOUT WARRANTY OF ANY KIND, either express or implied. See the
 * License for the specific language governing rights and limitations
 * under the License.
 *
 * Copyright (C) OpenMRS, LLC.  All Rights Reserved.
 */

package org.openmrs.module.emrapi.adt;

import org.joda.time.DateTime;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.openmrs.Encounter;
import org.openmrs.EncounterRole;
import org.openmrs.Location;
import org.openmrs.LocationTag;
import org.openmrs.Patient;
import org.openmrs.Provider;
import org.openmrs.Visit;
import org.openmrs.api.LocationService;
import org.openmrs.api.context.Context;
import org.openmrs.module.emrapi.EmrApiConstants;
import org.openmrs.module.emrapi.EmrApiProperties;
import org.openmrs.module.emrapi.visit.VisitDomainWrapper;
import org.openmrs.test.BaseModuleContextSensitiveTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.hamcrest.core.Is.is;
import static org.hamcrest.core.IsNull.notNullValue;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.openmrs.module.emrapi.TestUtils.hasProviders;
import static org.openmrs.module.emrapi.TestUtils.isJustNow;
import static org.openmrs.module.emrapi.adt.AdtAction.Type.ADMISSION;
import static org.openmrs.module.emrapi.adt.AdtAction.Type.DISCHARGE;

@RunWith(SpringJUnit4ClassRunner.class)
public class AdtServiceComponentTest extends BaseModuleContextSensitiveTest {

    @Autowired
    private AdtService service;

    @Autowired
    private EmrApiProperties emrApiProperties;

    @Autowired
    LocationService locationService;

    @Before
    public void setUp() throws Exception {
        executeDataSet("baseTestDataset.xml");
    }

    @Test
    public void integrationTest_ADT_workflow() {

        Provider provider = Context.getProviderService().getProvider(1);

        Patient patient = Context.getPatientService().getPatient(7);

        // parent location should support visits
        LocationTag supportsVisits = new LocationTag();
        supportsVisits.setName(EmrApiConstants.LOCATION_TAG_SUPPORTS_VISITS);
        locationService.saveLocationTag(supportsVisits);

        Location parentLocation = locationService.getLocation(2);
        parentLocation.addTag(supportsVisits);
        locationService.saveLocation(parentLocation);

        // add a child location where we'll do the actual check-in
        Location outpatientDepartment = new Location();
        outpatientDepartment.setName("Outpatient Clinic in Xanadu");
        outpatientDepartment.setParentLocation(parentLocation);
        locationService.saveLocation(outpatientDepartment);

        // add another child location for inpatient, that supports admissions
        LocationTag supportsAdmission = new LocationTag();
        supportsAdmission.setName(EmrApiConstants.LOCATION_TAG_SUPPORTS_ADMISSION);
        locationService.saveLocationTag(supportsAdmission);

        Location inpatientWard = new Location();
        inpatientWard.setName("Inpatient Ward in Xanadu");
        inpatientWard.setParentLocation(parentLocation);
        inpatientWard.addTag(supportsAdmission);
        locationService.saveLocation(inpatientWard);

        // step 1: check in the patient (which should create a visit and an encounter)
        Encounter checkInEncounter = service.checkInPatient(patient, outpatientDepartment, null, null, null,
                false);

        assertThat(checkInEncounter.getVisit(), notNullValue());
        assertThat(checkInEncounter.getPatient(), is(patient));
        assertThat(checkInEncounter.getEncounterDatetime(), isJustNow());
        assertThat(checkInEncounter.getVisit().getPatient(), is(patient));
        assertThat(checkInEncounter.getVisit().getStartDatetime(), isJustNow());
        assertThat(checkInEncounter.getAllObs().size(), is(0));

        Map<EncounterRole,Set<Provider>> providers = new HashMap<EncounterRole, Set<Provider>>();
        providers.put(Context.getEncounterService().getEncounterRole(1), Collections.singleton(provider));

        // step 2: admit the patient (which should create an encounter)
        Date admitDatetime = new Date();
        AdtAction admission = new AdtAction(checkInEncounter.getVisit(), inpatientWard, providers, ADMISSION);
        admission.setActionDatetime(admitDatetime);
        Encounter admitEncounter = service.createAdtEncounterFor(admission);

        assertThat(admitEncounter.getPatient(), is(patient));
        assertThat(admitEncounter.getVisit(), is(checkInEncounter.getVisit()));
        assertThat(admitEncounter.getEncounterDatetime(), is(admitDatetime));
        assertThat(admitEncounter.getLocation(), is(inpatientWard));
        assertThat(admitEncounter, hasProviders(providers));
        assertThat(admitEncounter.getAllObs().size(), is(0));
        assertTrue(new VisitDomainWrapper(admitEncounter.getVisit(), emrApiProperties).isAdmitted());

        // TODO transfer the patient within the hospital

        // step 3: discharge the patient (which should create an encounter)

        AdtAction discharge = new AdtAction(admitEncounter.getVisit(), inpatientWard, providers, DISCHARGE);
        Encounter dischargeEncounter = service.createAdtEncounterFor(discharge);

        assertThat(dischargeEncounter.getPatient(), is(patient));
        assertThat(dischargeEncounter.getVisit(), is(checkInEncounter.getVisit()));
        assertThat(dischargeEncounter.getEncounterDatetime(), isJustNow());
        assertThat(dischargeEncounter.getLocation(), is(inpatientWard));
        assertThat(dischargeEncounter, hasProviders(providers));
        assertFalse(new VisitDomainWrapper(admitEncounter.getVisit(), emrApiProperties).isAdmitted());
    }

	@Test
	public void integrationTest_ADT_workflow_duplicate_visits() throws Exception {
		final Integer numberOfThreads = 5;
		final CyclicBarrier threadsBarrier = new CyclicBarrier(numberOfThreads);

		Callable<Integer> checkInCall = new Callable<Integer>() {

			@Override
			public Integer call() throws Exception {
				Context.openSession();
				authenticate();
				try {
					LocationService locationService = Context.getLocationService();

					Patient patient = Context.getPatientService().getPatient(7);

					// parent location should support visits
					LocationTag supportsVisits = new LocationTag();
					supportsVisits.setName(EmrApiConstants.LOCATION_TAG_SUPPORTS_VISITS);
					locationService.saveLocationTag(supportsVisits);

					Location parentLocation = locationService.getLocation(2);
					parentLocation.addTag(supportsVisits);
					locationService.saveLocation(parentLocation);

					threadsBarrier.await();

					Encounter checkInEncounter = service.checkInPatient(patient, parentLocation, null, null, null,
					    false);

					return checkInEncounter.getVisit().getVisitId();
				}
				finally {
					Context.closeSession();
				}
			}
		};

		List<Callable<Integer>> checkInCalls = new ArrayList<Callable<Integer>>();
		for (int i = 0; i < numberOfThreads; i++) {
	        checkInCalls.add(checkInCall);
        }

		ExecutorService executorService = Executors.newFixedThreadPool(numberOfThreads);

		List<Future<Integer>> checkIns = executorService.invokeAll(checkInCalls);

		Integer visitId = null;
		for (Future<Integer> checkIn : checkIns) {
			Integer nextVisitId = checkIn.get();
			if (visitId != null) {
				assertThat(nextVisitId, is(visitId));
			} else {
				visitId = nextVisitId;
			}
		}
	}

    @Test
    public void integrationTest_createRetrospectiveVisit() throws Exception {

        // parent location should support visits
        LocationTag supportsVisits = new LocationTag();
        supportsVisits.setName(EmrApiConstants.LOCATION_TAG_SUPPORTS_VISITS);
        locationService.saveLocationTag(supportsVisits);

        Location parentLocation = locationService.getLocation(2);
        parentLocation.addTag(supportsVisits);
        locationService.saveLocation(parentLocation);

        // add a child location where we'll enter the actual visit
        Location outpatientDepartment = new Location();
        outpatientDepartment.setName("Outpatient Clinic in Xanadu");
        outpatientDepartment.setParentLocation(parentLocation);
        locationService.saveLocation(outpatientDepartment);

        Patient patient = Context.getPatientService().getPatient(7);

        // create a retrospective visit
        Date startDate = new DateTime(2012, 1, 1, 0, 0, 0).toDate();
        Date stopDate = new DateTime(2012, 1, 3, 0, 0, 0).toDate();

        VisitDomainWrapper visit = service.createRetrospectiveVisit(patient, outpatientDepartment, startDate, stopDate);

        // test that the visit was successfully created
        assertNotNull(visit);
        assertThat(visit.getVisit().getPatient(), is (patient));
        assertThat(visit.getStartDatetime(), is(startDate));
        assertThat(visit.getStopDatetime(), is(stopDate));
        assertThat(visit.getVisit().getLocation(), is(parentLocation));
        assertThat(visit.getVisit().getVisitType(), is(emrApiProperties.getAtFacilityVisitType()));
    }

    @Test
    public void test_getVisitsAndHasVisitDuring() throws Exception {

        // parent location should support visits
        LocationTag supportsVisits = new LocationTag();
        supportsVisits.setName(EmrApiConstants.LOCATION_TAG_SUPPORTS_VISITS);
        locationService.saveLocationTag(supportsVisits);

        Location parentLocation = locationService.getLocation(2);
        parentLocation.addTag(supportsVisits);
        locationService.saveLocation(parentLocation);

        // add a child location where we'll do the actual visit
        Location outpatientDepartment = new Location();
        outpatientDepartment.setName("Outpatient Clinic in Xanadu");
        outpatientDepartment.setParentLocation(parentLocation);
        locationService.saveLocation(outpatientDepartment);

        Patient patient = Context.getPatientService().getPatient(7);

        // create a retrospective visit
        Date startDate = new DateTime(2012, 1, 1, 0, 0, 0).toDate();
        Date stopDate = new DateTime(2012, 1, 3, 0, 0, 0).toDate();

        VisitDomainWrapper visit = service.createRetrospectiveVisit(patient, outpatientDepartment, startDate, stopDate);

        // start date falls within existing visit
        startDate =  new DateTime(2012, 1, 2, 0, 0, 0).toDate();
        stopDate =  new DateTime(2012, 1, 4, 0, 0, 0).toDate();
        assertTrue(service.hasVisitDuring(patient, outpatientDepartment, startDate, stopDate));

        // end date falls within existing visit
        startDate =  new DateTime(2011, 12, 29, 0, 0, 0).toDate();
        stopDate =  new DateTime(2012, 1, 2, 0, 0, 0).toDate();
        assertTrue(service.hasVisitDuring(patient, outpatientDepartment, startDate, stopDate));

        // range falls within existing visit
        startDate =  new DateTime(2012, 1, 1, 12, 0, 0).toDate();
        stopDate =  new DateTime(2012, 1, 2, 0, 0, 0).toDate();
        assertTrue(service.hasVisitDuring(patient, outpatientDepartment, startDate, stopDate));

        // range encompasses existing visit
        startDate =  new DateTime(2011, 12, 29, 0, 0, 0).toDate();
        stopDate =  new DateTime(2012, 1, 5, 0, 0, 0).toDate();
        assertTrue(service.hasVisitDuring(patient, outpatientDepartment, startDate, stopDate));

        // no stopDate specified
        startDate =  new DateTime(2011, 12, 29, 0, 0, 0).toDate();
        assertTrue(service.hasVisitDuring(patient, outpatientDepartment, startDate, null));

        // range is before existing visit
        startDate =  new DateTime(2011, 12, 29, 0, 0, 0).toDate();
        stopDate =  new DateTime(2011, 12, 30, 0, 0, 0).toDate();
        assertFalse(service.hasVisitDuring(patient, outpatientDepartment, startDate, stopDate));

        // range is after existing visit
        startDate =  new DateTime(2012, 1, 4, 0, 0, 0).toDate();
        stopDate =  new DateTime(2012, 1, 5, 0, 0, 0).toDate();
        assertFalse(service.hasVisitDuring(patient, outpatientDepartment, startDate, stopDate));

        // now lets create an active visit to make sure that hasVisitDuring properly handles visits with no stopDate
        Date now = new Date();
        Date futureDate = new DateTime(3000, 12, 30, 0, 0, 0).toDate(); //  this test will start to fail after the year 3000! :)

        service.ensureActiveVisit(patient, outpatientDepartment);
        assertTrue(service.hasVisitDuring(patient, outpatientDepartment, now, futureDate));
        assertFalse(service.hasVisitDuring(patient, outpatientDepartment, stopDate, now));

        // now lets just add another retrospective visit to do a quick test of the getVisits method
        startDate = new DateTime(2012, 1, 5, 0, 0, 0).toDate();
        stopDate = new DateTime(2012, 1, 7, 0, 0, 0).toDate();

        VisitDomainWrapper anotherVisit = service.createRetrospectiveVisit(patient, outpatientDepartment, startDate, stopDate);

        startDate = new DateTime(2012, 1, 2, 0, 0, 0).toDate();
        stopDate = new DateTime(2012, 1, 6, 0, 0, 0).toDate();
        List<VisitDomainWrapper> visitDomainWrappers = service.getVisits(patient, outpatientDepartment, startDate, stopDate);

        assertThat(visitDomainWrappers.size(), is(2));

        List<Visit> visits = new ArrayList<Visit>();
        for (VisitDomainWrapper visitDomainWrapper : visitDomainWrappers) {
            visits.add(visitDomainWrapper.getVisit());
        }

        assertTrue(visits.contains(visit.getVisit()));
        assertTrue(visits.contains(anotherVisit.getVisit()));

    }

}
